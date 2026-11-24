package console;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Validates NDJSON reports and atomically updates report, asset, and alert state. */
@Service
class Ingest {
    private static final Logger LOG = LoggerFactory.getLogger(Ingest.class);
    private static final int MAX_BYTES = 5_000_000;
    private final JdbcTemplate db;
    private final ObjectReader reader;
    private final ObjectMapper json;
    private final Zones zones;

    /** Creates the ingest service and its strict JSON reader. */
    Ingest(JdbcTemplate db, ObjectMapper mapper, Zones zones) {
        this.db = db;
        this.json = mapper;
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.zones = zones;
    }

    /** Represents one validated position report. */
    record Report(String id, String asset, Instant time, double lat, double lon, String kind,
                  Double speed, Double heading, String source) {}
    /** Describes one rejected non-empty input line. */
    record Rejection(int line, String code) {}
    /** Retains a rejected line with its persisted raw representation. */
    record Invalid(Rejection rejection, String raw) {}
    /** Contains the valid and invalid lines read from one request. */
    record Batch(List<Report> reports, List<Invalid> invalid) {}
    /** Summarizes the persisted outcome of one ingest batch. */
    record Result(int received, int accepted, int duplicates, int rejected, List<Rejection> rejections) {}
    /** Captures the current position used while advancing an asset. */
    record Current(String reportId, Instant time, double lat, double lon) {}
    /** Identifies an entry alert to insert after current-state advancement. */
    record AlertCandidate(String assetId, String zoneId, String reportId) {}

    /** Streams, bounds, and validates an NDJSON request outside the transaction. */
    Batch read(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/x-ndjson")) {
            throw new Problem(415, "UNSUPPORTED_MEDIA_TYPE");
        }
        if (request.getContentLengthLong() > MAX_BYTES) throw new Problem(413, "PAYLOAD_TOO_LARGE");

        var reports = new ArrayList<Report>();
        var invalid = new ArrayList<Invalid>();
        int line = 0;
        try (var input = new LimitedInputStream(request.getInputStream(), MAX_BYTES);
             var lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = lines.readLine()) != null) {
                if (raw.isEmpty()) continue;
                if (++line > 1000) throw new Problem(413, "PAYLOAD_TOO_LARGE");
                try {
                    reports.add(parse(raw));
                } catch (BadReport e) {
                    invalid.add(new Invalid(new Rejection(line, e.getMessage()), raw.replace("\u0000", "\\u0000")));
                }
            }
        } catch (PayloadTooLarge e) {
            throw new Problem(413, "PAYLOAD_TOO_LARGE");
        }
        return new Batch(List.copyOf(reports), List.copyOf(invalid));
    }

    /** Parses and validates one report line. */
    private Report parse(String raw) {
        JsonNode n;
        try {
            n = reader.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new BadReport("MALFORMED_JSON");
        }
        if (n == null || !n.isObject()) throw new BadReport("MALFORMED_JSON");
        for (String field : List.of("reportId", "assetId", "observedAt", "lat", "lon")) {
            JsonNode value = n.path(field);
            if (value.isMissingNode() || value.isNull() || value.isTextual() && value.textValue().isEmpty()) {
                throw new BadReport("MISSING_FIELD");
            }
        }

        double lat = coordinate(n.path("lat"), 90);
        double lon = coordinate(n.path("lon"), 180);
        Instant time;
        try {
            time = Instant.parse(n.path("observedAt").asText());
            if (time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || !time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) {
                throw new DateTimeException("Timestamp outside PostgreSQL range");
            }
        } catch (DateTimeException e) {
            throw new BadReport("INVALID_TIMESTAMP");
        }

        String asset = requiredText(n.path("assetId"));
        String id = requiredText(n.path("reportId"));
        if (!asset.matches("[A-Z0-9][A-Z0-9-]{2,31}") || id.codePointCount(0, id.length()) > 64) {
            throw new BadReport("INVALID_FIELD");
        }
        return new Report(id, asset, time, lat, lon, optionalText(n.path("kind")),
                optionalNumber(n.path("speedKn")), optionalNumber(n.path("headingDeg")),
                optionalText(n.path("source")));
    }

    /** Reads a finite coordinate within the supplied absolute bound. */
    private static double coordinate(JsonNode value, int max) {
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || Math.abs(value.doubleValue()) > max) {
            throw new BadReport("INVALID_COORDINATE");
        }
        return value.doubleValue();
    }

    /** Reads a required NUL-free text field. */
    private static String requiredText(JsonNode value) {
        if (!value.isTextual() || value.textValue().indexOf(0) >= 0) throw new BadReport("INVALID_FIELD");
        return value.textValue();
    }

    /** Reads an optional NUL-free text field. */
    private static String optionalText(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().indexOf(0) >= 0) throw new BadReport("INVALID_FIELD");
        return value.textValue();
    }

    /** Reads an optional finite numeric field. */
    private static Double optionalNumber(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw new BadReport("INVALID_FIELD");
        return value.doubleValue();
    }

    /** Signals a line-level validation failure without allocating a stack trace. */
    private static class BadReport extends RuntimeException {
        /** Creates a report failure with its stable rejection code. */
        BadReport(String code) {
            super(code, null, false, false);
        }
    }

    /** Persists one parsed batch and advances affected current state atomically. */
    @Transactional
    public Result store(Batch batch, String requestId) {
        List<Report> inserted = insertReports(batch.reports());
        if (!inserted.isEmpty()) updateAssetsAndAlerts(batch.reports(), inserted);

        db.batchUpdate(
                "insert into ingest_rejections(request_id,line,code,raw_line) values (?,?,?,?)",
                batch.invalid().stream().map(item -> new Object[]{
                        requestId, item.rejection().line(), item.rejection().code(), item.raw()
                }).toList()
        );
        int accepted = inserted.size();
        int rejected = batch.invalid().size();
        int duplicates = batch.reports().size() - accepted;
        int received = batch.reports().size() + rejected;
        db.update("insert into ingest_batches(received,accepted,duplicates,rejected) values (?,?,?,?)",
                received, accepted, duplicates, rejected);
        LOG.info("Ingest processed received={} accepted={} duplicates={} rejected={}",
                received, accepted, duplicates, rejected);
        return new Result(received, accepted, duplicates, rejected,
                batch.invalid().stream().map(Invalid::rejection).toList());
    }

    /** Inserts the first occurrence of each previously unseen report ID. */
    private List<Report> insertReports(List<Report> reports) {
        if (reports.isEmpty()) return List.of();

        // The first payload for an ID wins within a batch; sorting also gives concurrent
        // transactions a consistent order before they lock the affected asset rows.
        Map<String, Report> firstById = new LinkedHashMap<>();
        reports.forEach(report -> firstById.putIfAbsent(report.id(), report));
        List<Report> candidates = firstById.values().stream()
                .sorted(Comparator.comparing(Report::id))
                .toList();

        String values = String.join(",", Collections.nCopies(candidates.size(), "(?,?,?,?,?,?,?,?,?,?)"));
        var arguments = new ArrayList<Object>(candidates.size() * 10);
        for (Report report : candidates) {
            arguments.add(report.id());
            arguments.add(report.asset());
            arguments.add(dbTime(report.time()));
            arguments.add(nanoRemainder(report.time()));
            arguments.add(report.lat());
            arguments.add(report.lon());
            arguments.add(report.kind());
            arguments.add(report.speed());
            arguments.add(report.heading());
            arguments.add(report.source());
        }
        return db.query("""
                insert into reports(
                  report_id,asset_id,observed_at,observed_ns,lat,lon,kind,speed_kn,heading_deg,source
                ) values %s
                on conflict (report_id) do nothing
                returning report_id,asset_id,observed_at,observed_ns,lat,lon,kind,speed_kn,heading_deg,source
                """.formatted(values), this::reportRow, arguments.toArray());
    }

    /** Locks affected assets, advances their current positions, and creates entry alerts. */
    private void updateAssetsAndAlerts(List<Report> delivered, List<Report> inserted) {
        List<String> assetIds = inserted.stream().map(Report::asset).distinct().sorted().toList();
        String assetValues = String.join(",", Collections.nCopies(assetIds.size(), "(?)"));
        db.update("insert into assets(asset_id) values " + assetValues + " on conflict (asset_id) do nothing",
                assetIds.toArray());

        // Asset rows are the cross-replica mutex. Sorted locking avoids deadlocks while
        // allowing transactions that touch unrelated assets to proceed concurrently.
        String in = String.join(",", Collections.nCopies(assetIds.size(), "?"));
        Map<String, Current> current = new HashMap<>();
        db.query("""
                select asset_id,current_report_id,current_observed_at,current_observed_ns,lat,lon
                from assets where asset_id in (%s) order by asset_id for update
                """.formatted(in), rs -> {
            OffsetDateTime observed = rs.getObject("current_observed_at", OffsetDateTime.class);
            current.put(rs.getString("asset_id"), observed == null ? null : new Current(
                    rs.getString("current_report_id"),
                    observed.toInstant().plusNanos(rs.getInt("current_observed_ns")),
                    rs.getDouble("lat"),
                    rs.getDouble("lon")
            ));
        }, assetIds.toArray());

        Map<String, Report> insertedById = new HashMap<>();
        inserted.forEach(report -> insertedById.put(report.id(), report));
        Set<String> handled = new HashSet<>();
        Set<String> changedAssets = new HashSet<>();
        List<AlertCandidate> alerts = new ArrayList<>();
        // Preserve delivery order so equal-time reports leave the first committed current
        // position in place; late reports remain in history without creating entry alerts.
        for (Report deliveredReport : delivered) {
            if (!handled.add(deliveredReport.id())) continue;
            Report report = insertedById.get(deliveredReport.id());
            if (report == null) continue;

            Current old = current.get(report.asset());
            if (old != null && !report.time().isAfter(old.time())) continue;
            Set<String> entering = zones.inside(report.lon(), report.lat());
            if (old != null) entering.removeAll(zones.inside(old.lon(), old.lat()));
            entering.forEach(zone -> alerts.add(new AlertCandidate(report.asset(), zone, report.id())));
            current.put(report.asset(), new Current(report.id(), report.time(), report.lat(), report.lon()));
            changedAssets.add(report.asset());
        }

        db.batchUpdate("""
                update assets set current_report_id=?,current_observed_at=?,current_observed_ns=?,lat=?,lon=?,zone_ids=?
                where asset_id=?
                """, changedAssets.stream().sorted().map(asset -> {
            Current value = current.get(asset);
            return new Object[]{
                    value.reportId(), dbTime(value.time()), nanoRemainder(value.time()),
                    value.lat(), value.lon(), zoneIds(value.lon(), value.lat()), asset
            };
        }).toList());
        db.batchUpdate("""
                insert into alerts(asset_id,zone_id,trigger_report_id) values (?,?,?)
                on conflict (asset_id,zone_id) where status in ('OPEN','CLAIMED') do nothing
                """, alerts.stream().map(alert -> new Object[]{
                alert.assetId(), alert.zoneId(), alert.reportId()
        }).toList());
    }

    /** Maps a returned database report row to the domain representation. */
    private Report reportRow(ResultSet rs, int row) throws SQLException {
        Instant time = rs.getObject("observed_at", OffsetDateTime.class).toInstant()
                .plusNanos(rs.getInt("observed_ns"));
        return new Report(
                rs.getString("report_id"), rs.getString("asset_id"), time,
                rs.getDouble("lat"), rs.getDouble("lon"), rs.getString("kind"),
                (Double) rs.getObject("speed_kn"), (Double) rs.getObject("heading_deg"), rs.getString("source")
        );
    }

    /** Serializes all zone memberships for a current position. */
    private String zoneIds(double lon, double lat) {
        try {
            return json.writeValueAsString(zones.membership(lon, lat));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize zone membership", e);
        }
    }

    /** Converts an instant to the database's microsecond timestamp component. */
    private static OffsetDateTime dbTime(Instant time) {
        // PostgreSQL stores microseconds; observed_ns carries the remaining three digits.
        return OffsetDateTime.ofInstant(time.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    /** Returns the sub-microsecond nanosecond remainder stored separately. */
    private static int nanoRemainder(Instant time) {
        return time.getNano() % 1000;
    }

    /** Signals that a streaming request exceeded its byte budget. */
    private static class PayloadTooLarge extends IOException {}

    /** Enforces a byte limit even when the request omits a reliable content length. */
    private static class LimitedInputStream extends FilterInputStream {
        private final long maximum;
        private long count;

        /** Wraps an input stream with the supplied maximum byte count. */
        LimitedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        /** Reads one byte while enforcing the configured limit. */
        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > maximum) throw new PayloadTooLarge();
            return value;
        }

        /** Reads a block without consuming beyond the configured limit. */
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            long remaining = maximum - count;
            if (remaining == 0) {
                if (super.read() >= 0) throw new PayloadTooLarge();
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) count += read;
            return read;
        }
    }
}
