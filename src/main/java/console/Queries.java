package console;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Executes bounded read queries and transactional alert lifecycle commands. */
@Service
class Queries {
    private final JdbcTemplate db;
    private final ObjectReader zoneIdsReader;
    private static final String ALERT = """
        select a.*,r.observed_at as trigger_observed_at from alerts a
        join reports r on r.report_id=a.trigger_report_id
        """;
    private static final String ASSET = """
        select r.*,a.asset_id,a.zone_ids
        from assets a
        join lateral (
          -- Prevent flattening into a full-history hash join as reports grow.
          select report_id,observed_at,observed_ns,lat,lon,source,kind,speed_kn,heading_deg
          from reports
          where report_id=a.current_report_id
          offset 0
        ) r on true
        """;
    private static final String SOURCE_TYPE = "coalesce(source_type,classify_source(source))";

    /** Creates the query service and its stored-zone decoder. */
    Queries(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.zoneIdsReader = json.readerForListOf(String.class);
    }

    /** Checks database connectivity with a minimal query. */
    boolean ready() { return Integer.valueOf(1).equals(db.queryForObject("select 1", Integer.class)); }

    /** Returns aggregate ingest, current-asset, and alert-status counts. */
    Map<String, Object> stats() {
        var totals = db.queryForMap("""
            select coalesce(sum(received),0) received,coalesce(sum(accepted),0) accepted,
              coalesce(sum(duplicates),0) duplicates,coalesce(sum(rejected),0) rejected,
              (select count(*) from assets where current_report_id is not null) assets,
              (select count(*) from alerts where status='OPEN') open,
              (select count(*) from alerts where status='CLAIMED') claimed,
              (select count(*) from alerts where status='RESOLVED') resolved
            from ingest_batches
            """);
        Map<String, Object> status = new LinkedHashMap<>();
        for (String key : List.of("open", "claimed", "resolved")) status.put(key, totals.remove(key));
        totals.put("alerts", status);
        return totals;
    }

    /** Returns a bounded current-asset snapshot inside a viewport. */
    Map<String, Object> assets(String bbox, int limit) {
        double[] bounds;
        try { bounds = Arrays.stream(bbox.split(",", -1)).mapToDouble(Double::parseDouble).toArray(); }
        catch (NumberFormatException e) { throw Problem.validation(); }
        if (bounds.length != 4 || !(Math.abs(bounds[0]) <= 180) || !(Math.abs(bounds[2]) <= 180)
                || !(Math.abs(bounds[1]) <= 90) || !(Math.abs(bounds[3]) <= 90) || bounds[1] > bounds[3]) {
            throw Problem.validation();
        }
        // A west bound greater than the east bound denotes a viewport crossing the antimeridian.
        String longitude = bounds[0] <= bounds[2] ? "a.lon between ? and ?" : "(a.lon >= ? or a.lon <= ?)";
        return bounded(db.query(ASSET + " where a.lat between ? and ? and " + longitude + " order by a.asset_id collate \"C\" limit ?",
                this::assetRow, bounds[1], bounds[3], bounds[0], bounds[2], limit + 1), limit);
    }

    /** Returns the current position of one asset. */
    Map<String, Object> asset(String id) {
        return first(db.query(ASSET + " where a.asset_id=?", this::assetRow, id));
    }

    /** Returns a bounded and optionally filtered track for one asset. */
    Map<String, Object> track(String id, String from, String to, int limit, boolean recent, String sourceType) {
        asset(id);
        var arguments = new ArrayList<Object>();
        arguments.add(id);
        StringBuilder sql = new StringBuilder(
                "select reports.*," + SOURCE_TYPE + " effective_source_type from reports where asset_id=?");
        if (sourceType != null) {
            if (!Set.of("SATELLITE", "GROUND", "MOBILE", "UNKNOWN").contains(sourceType)) {
                throw Problem.validation();
            }
            sql.append(" and ").append(SOURCE_TYPE).append("=?");
            arguments.add(sourceType);
        }
        if (from != null) {
            sql.append(" and (observed_at, observed_ns) >= (?,?)");
            addTime(arguments, from);
        }
        if (to != null) {
            sql.append(" and (observed_at, observed_ns) <= (?,?)");
            addTime(arguments, to);
        }
        arguments.add(limit + 1);
        String direction = recent ? " desc" : " asc";
        var rows = db.query(sql + " order by observed_at" + direction + ", observed_ns" + direction
                + ", report_id" + direction + " limit ?", this::classifiedTrackRow,
                arguments.toArray());
        // Fetch one sentinel row to report truncation without a separate count query.
        boolean truncated = rows.size() > limit;
        var items = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        // Recent queries read newest-first for the index, then restore chronological API order.
        if (recent) Collections.reverse(items);
        return Map.of("items", items, "truncated", truncated);
    }

    /** Appends a parsed timestamp's database components to query arguments. */
    private static void addTime(List<Object> args, String value) {
        try {
            Instant time = Instant.parse(value);
            args.add(OffsetDateTime.ofInstant(time.truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC));
            args.add(time.getNano() % 1000);
        } catch (DateTimeException e) { throw Problem.validation(); }
    }

    /** Maps a historical report row to API track fields. */
    private Map<String, Object> trackRow(ResultSet rs, int row) throws SQLException {
        return fields("reportId", rs.getString("report_id"), "observedAt", time(rs, "observed_at"),
                "lat", rs.getDouble("lat"), "lon", rs.getDouble("lon"), "source", rs.getString("source"));
    }

    /** Adds the effective source classification to a track row. */
    private Map<String, Object> classifiedTrackRow(ResultSet rs, int row) throws SQLException {
        var result = trackRow(rs, row);
        result.put("sourceType", rs.getString("effective_source_type"));
        return result;
    }

    /** Maps a joined current-report row to API asset fields. */
    private Map<String, Object> assetRow(ResultSet rs, int row) throws SQLException {
        return fields("reportId", rs.getString("report_id"), "observedAt", time(rs, "observed_at"),
                "lat", rs.getDouble("lat"), "lon", rs.getDouble("lon"), "source", rs.getString("source"),
                "assetId", rs.getString("asset_id"), "kind", rs.getString("kind"),
                "speedKn", rs.getObject("speed_kn"), "headingDeg", rs.getObject("heading_deg"),
                "zoneIds", zoneIds(rs.getString("zone_ids")));
    }

    /** Decodes persisted zone membership JSON. */
    private List<String> zoneIds(String value) throws SQLException {
        try {
            return zoneIdsReader.readValue(value);
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid stored zone membership", e);
        }
    }

    /** Returns a filtered cursor-based alert page. */
    Map<String, Object> alerts(String status, String asset, String zone, String cursor, int limit) {
        var args = new ArrayList<Object>();
        StringBuilder sql = new StringBuilder(ALERT);
        sql.append(" where a.id > ?");
        args.add(cursor == null ? 0L : positive(cursor, Problem.validation()));
        if (status != null && !Set.of("OPEN","CLAIMED","RESOLVED").contains(status)) throw Problem.validation();
        filter(sql, args, "status", status);
        filter(sql, args, "asset_id", asset);
        filter(sql, args, "zone_id", zone);
        args.add(limit + 1);
        var rows = db.query(sql + " order by a.id limit ?", this::alertRow, args.toArray());
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return fields("items", items, "nextCursor", more ? items.getLast().get("alertId") : null);
    }

    /** Appends an optional equality filter and argument. */
    private static void filter(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null) {
            sql.append(" and a.").append(column).append("=?");
            args.add(value);
        }
    }

    /** Returns one alert by identifier. */
    Map<String, Object> alert(String id) {
        return first(db.query(ALERT + " where a.id=?", this::alertRow, positive(id, Problem.missing())));
    }

    /** Returns a cursor-based page of one alert's immutable audit entries. */
    Map<String, Object> audit(String id, long after, int limit) {
        alert(id);
        var rows = db.query("select * from alert_audit where alert_id=? and id>? order by id limit ?", (rs, row) ->
                fields("at", time(rs, "at"), "actor", rs.getString("actor"), "action", rs.getString("action"),
                        "fromStatus", rs.getString("from_status"), "toStatus", rs.getString("to_status"),
                        "entryId", rs.getLong("id")), positive(id, Problem.missing()), after, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return fields("items", items, "nextCursor", more ? items.getLast().get("entryId") : null);
    }

    /** Applies a validated claim, release, or resolve transition under a row lock. */
    @Transactional
    public Map<String, Object> command(String id, String action, Auth.User caller, String note) {
        long key = positive(id, Problem.missing());
        var current = first(db.query(ALERT + " where a.id=? for update of a", this::alertRow, key));
        String status = (String) current.get("status");
        String user = caller.username();
        boolean owner = user.equals(current.get("claimedBy"));
        switch (action) {
            case "claim" -> {
                if ("RESOLVED".equals(status)) throw new Problem(409, "INVALID_STATE");
                if (owner) return current;
                if ("CLAIMED".equals(status)) throw new Problem(409, "ALERT_ALREADY_CLAIMED");
                actor(user);
                db.update("update alerts set status='CLAIMED',claimed_by=?,claimed_at=clock_timestamp() where id=?", user, key);
            }
            case "release", "resolve" -> {
                if ("resolve".equals(action)) {
                    if (note == null || note.isBlank() || note.indexOf(0) >= 0 || note.codePointCount(0, note.length()) > 500) {
                        throw Problem.validation();
                    }
                    if ("RESOLVED".equals(status) && user.equals(current.get("resolvedBy"))) return current;
                }
                if (!"CLAIMED".equals(status)) throw new Problem(409, "INVALID_STATE");
                if (!owner && !"SUPERVISOR".equals(caller.role())) throw new Problem(403, "NOT_ALERT_OWNER");
                actor(user);
                if ("release".equals(action)) {
                    db.update("update alerts set status='OPEN',claimed_by=null,claimed_at=null where id=?", key);
                } else {
                    db.update("""
                        update alerts set status='RESOLVED',resolved_by=?,resolved_at=clock_timestamp(),
                          resolution_note=? where id=?
                        """, user, note, key);
                }
            }
            default -> throw Problem.missing();
        }
        return alert(id);
    }

    /** Sets the transaction-local actor consumed by the audit trigger. */
    private void actor(String user) { db.queryForObject("select set_config('console.actor',?,true)", String.class, user); }

    /** Maps an alert query row to API fields. */
    private Map<String, Object> alertRow(ResultSet rs, int row) throws SQLException {
        return fields("alertId", rs.getString("id"), "assetId", rs.getString("asset_id"),
                "zoneId", rs.getString("zone_id"), "status", rs.getString("status"),
                "openedAt", time(rs, "opened_at"), "triggerReportId", rs.getString("trigger_report_id"),
                "triggerObservedAt", time(rs, "trigger_observed_at"), "claimedBy", rs.getString("claimed_by"),
                "claimedAt", time(rs, "claimed_at"), "resolvedBy", rs.getString("resolved_by"),
                "resolvedAt", time(rs, "resolved_at"), "resolutionNote", rs.getString("resolution_note"));
    }

    /** Formats a nullable database timestamp for API output. */
    private static String time(ResultSet rs, String field) throws SQLException {
        OffsetDateTime t = rs.getObject(field, OffsetDateTime.class);
        return t == null ? null : App.time(t.toInstant());
    }

    /** Parses a strictly positive identifier or throws the supplied API problem. */
    private static long positive(String raw, Problem error) {
        try {
            long value = Long.parseLong(raw);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) { }
        throw error;
    }

    /** Returns the first row or raises the missing-resource problem. */
    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) throw Problem.missing();
        return rows.getFirst();
    }

    /** Converts a sentinel-row result into a bounded response. */
    private static Map<String, Object> bounded(List<Map<String, Object>> rows, int limit) {
        boolean truncated = rows.size() > limit;
        return Map.of("items", truncated ? rows.subList(0, limit) : rows, "truncated", truncated);
    }

    /** Builds an insertion-ordered response map from alternating key/value pairs. */
    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
}