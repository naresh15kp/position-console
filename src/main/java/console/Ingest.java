package console;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
class Ingest {
    private static final Logger LOG = LoggerFactory.getLogger(Ingest.class);
    private static final int MAX_BYTES = 5_000_000;
    private final JdbcTemplate db;
    private final ObjectReader reader;
    private final Zones zones;

    Ingest(JdbcTemplate db, ObjectMapper mapper, Zones zones) {
        this.db = db;
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.zones = zones;
    }

    record Report(String id, String asset, Instant time, double lat, double lon, String kind,
                  Double speed, Double heading, String source) {}
    record Rejection(int line, String code) {}
    record Invalid(Rejection rejection, String raw) {}
    record Batch(List<Report> reports, List<Invalid> invalid) {}
    record Result(int received, int accepted, int duplicates, int rejected, List<Rejection> rejections) {}
    record Current(Instant time, double lat, double lon) {}

    Batch read(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/x-ndjson")) {
            throw new Problem(415, "UNSUPPORTED_MEDIA_TYPE");
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new Problem(413, "PAYLOAD_TOO_LARGE");
        var reports = new ArrayList<Report>();
        var invalid = new ArrayList<Invalid>();
        int line = 0;
        for (String raw : new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n", -1)) {
            if (raw.isBlank()) continue;
            if (++line > 1000) throw new Problem(413, "PAYLOAD_TOO_LARGE");
            try {
                reports.add(parse(raw));
            } catch (BadReport e) {
                invalid.add(new Invalid(new Rejection(line, e.getMessage()), raw.replace("\u0000", "\\u0000")));
            }
        }
        return new Batch(reports, invalid);
    }

    private Report parse(String raw) {
        JsonNode n;
        try { n = reader.readTree(raw); }
        catch (JsonProcessingException e) { throw new BadReport("MALFORMED_JSON"); }
        if (n == null || !n.isObject()) throw new BadReport("MALFORMED_JSON");
        for (String f : List.of("reportId", "assetId", "observedAt", "lat", "lon")) {
            JsonNode item = n.path(f);
            if (item.isMissingNode() || item.isNull() || item.isTextual() && item.textValue().isEmpty()) {
                throw new BadReport("MISSING_FIELD");
            }
        }
        double lat = coordinate(n.path("lat"), 90);
        double lon = coordinate(n.path("lon"), 180);
        Instant time;
        try {
            time = Instant.parse(n.path("observedAt").asText());
            if (time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || !time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) throw new DateTimeException("Range");
        } catch (DateTimeException e) { throw new BadReport("INVALID_TIMESTAMP"); }
        String asset = optionalText(n.path("assetId"));
        String id = optionalText(n.path("reportId"));
        if (asset == null || !asset.matches("[A-Z0-9][A-Z0-9-]{2,31}") || id == null
                || id.codePointCount(0, id.length()) > 64) throw new BadReport("INVALID_FIELD");
        return new Report(id, asset, time, lat, lon, optionalText(n.path("kind")),
                optionalNumber(n.path("speedKn")), optionalNumber(n.path("headingDeg")), optionalText(n.path("source")));
    }

    private static double coordinate(JsonNode n, int max) {
        if (!n.isNumber() || !(Math.abs(n.doubleValue()) <= max)) throw new BadReport("INVALID_COORDINATE");
        return n.doubleValue();
    }

    private static String optionalText(JsonNode n) {
        return n.isTextual() && n.textValue().indexOf(0) < 0 ? n.textValue() : null;
    }

    private static Double optionalNumber(JsonNode n) {
        return n.isNumber() && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }

    private static class BadReport extends RuntimeException {
        BadReport(String code) { super(code, null, false, false); }
    }

    @Transactional
    public Result store(Batch batch, String requestId) {
        int accepted = 0;
        if (!batch.reports().isEmpty()) {
            // Serialize writers across replicas. No alert command takes this lock.
            db.query("select pg_advisory_xact_lock(184077101)", rs -> { });
            for (Report report : batch.reports()) if (storeReport(report)) accepted++;
        }
        db.batchUpdate("insert into rejections(request_id,line,code,raw_line) values (?,?,?,?)",
                batch.invalid().stream().map(item -> new Object[]{requestId, item.rejection().line(),
                        item.rejection().code(), item.raw()}).toList());
        int rejected = batch.invalid().size();
        int duplicates = batch.reports().size() - accepted;
        int received = batch.reports().size() + rejected;
        db.update("insert into ingest_batches(received,accepted,duplicates,rejected) values (?,?,?,?)",
                received, accepted, duplicates, rejected);
        LOG.info("Ingest processed received={} accepted={} duplicates={} rejected={}", received, accepted, duplicates, rejected);
        return new Result(received, accepted, duplicates, rejected, batch.invalid().stream().map(Invalid::rejection).toList());
    }

    private boolean storeReport(Report report) {
        var dbTime = OffsetDateTime.ofInstant(report.time().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        int stored = db.update("""
                insert into reports(report_id,asset_id,observed_at,observed_ns,lat,lon,kind,speed_kn,heading_deg,source)
                values (?,?,?,?,?,?,?,?,?,?) on conflict (report_id) do nothing
                """, report.id(), report.asset(), dbTime, report.time().getNano() % 1000, report.lat(), report.lon(),
                report.kind(), report.speed(), report.heading(), report.source());
        if (stored == 0) return false;
        var old = db.query("""
                select r.observed_at,r.observed_ns,r.lat,r.lon from positions p
                join reports r on r.report_id=p.report_id where p.asset_id=?
                """, (rs, row) -> new Current(rs.getObject(1, OffsetDateTime.class).toInstant().plusNanos(rs.getLong(2)),
                rs.getDouble(3), rs.getDouble(4)), report.asset());
        if (!old.isEmpty() && !report.time().isAfter(old.getFirst().time())) return true;
        db.update("""
                insert into positions(asset_id,report_id,lat,lon) values (?,?,?,?)
                on conflict (asset_id) do update set report_id=excluded.report_id,lat=excluded.lat,lon=excluded.lon
                """, report.asset(), report.id(), report.lat(), report.lon());
        Set<String> entering = zones.inside(report.lon(), report.lat());
        if (!old.isEmpty()) entering.removeAll(zones.inside(old.getFirst().lon(), old.getFirst().lat()));
        for (String zone : entering) {
            db.update("""
                    insert into alerts(asset_id,zone_id,trigger_report_id) values (?,?,?)
                    on conflict (asset_id,zone_id) where status in ('OPEN','CLAIMED') do nothing
                    """, report.asset(), zone, report.id());
        }
        return true;
    }
}