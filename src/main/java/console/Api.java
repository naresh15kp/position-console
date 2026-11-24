package console;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

/** Exposes authentication, ingest, asset, zone, and alert HTTP endpoints. */
@RestController
class Api {
    private final Auth auth;
    private final Ingest ingest;
    private final Queries queries;
    private final Zones zones;

    /** Creates the API controller from its domain services. */
    Api(Auth auth, Ingest ingest, Queries queries, Zones zones) {
        this.auth = auth;
        this.ingest = ingest;
        this.queries = queries;
        this.zones = zones;

    }

    /** Reports whether the backing database is reachable. */
    @GetMapping("/health")
    Object health() {
        if (!queries.ready()) throw new Problem(503, "INTERNAL_ERROR");
        return Map.of("status", "UP");
    }

    /** Authenticates an account and returns a bearer token. */
    @PostMapping("/api/v1/auth/login")
    Object login(@RequestBody JsonNode body) { return auth.login(body); }

    /** Reads and stores one authenticated NDJSON report batch. */
    @PostMapping("/api/v1/reports/batch")
    Object batch(HttpServletRequest request) throws IOException {
        auth.require(request.getHeader("Authorization"), "INGEST");
        return ingest.store(ingest.read(request), MDC.get("requestId"));
    }

    /** Returns aggregate ingest, asset, and alert counts. */
    @GetMapping("/api/v1/stats")
    Object stats(HttpServletRequest request) {
        auth.require(request.getHeader("Authorization"));
        return queries.stats();
    }

    /** Returns configured zone geometry to an authorized operator. */
    @GetMapping("/api/v1/zones")
    Object zones(HttpServletRequest request) {
        operator(request);
        return zones.display;
    }

    /** Returns current assets within a bounded viewport. */
    @GetMapping("/api/v1/assets")
    Object assets(HttpServletRequest request, @RequestParam String bbox, @RequestParam(defaultValue="1000") int limit) {
        operator(request);
        return queries.assets(bbox, bounded(limit, 5000));
    }

    /** Returns the current state of one asset. */
    @GetMapping("/api/v1/assets/{assetId}")
    Object asset(HttpServletRequest request, @PathVariable String assetId) {
        operator(request);
        return queries.asset(assetId);
    }

    /** Returns a filtered, bounded history for one asset. */
    @GetMapping("/api/v1/assets/{assetId}/track")
    Object track(HttpServletRequest request, @PathVariable String assetId, @RequestParam(required=false) String from,
                 @RequestParam(required=false) String to, @RequestParam(defaultValue="500") int limit,
                 @RequestParam(defaultValue="false") boolean recent,
                 @RequestParam(required=false) String sourceType) {
        operator(request);
        return queries.track(assetId, from, to, bounded(limit, 5000), recent, sourceType);
    }

    /** Returns a filtered page of alerts. */
    @GetMapping("/api/v1/alerts")
    Object alerts(HttpServletRequest request, @RequestParam(required=false) String status,
                  @RequestParam(required=false) String assetId, @RequestParam(required=false) String zoneId,
                  @RequestParam(required=false) String cursor, @RequestParam(defaultValue="100") int limit) {
        operator(request);
        return queries.alerts(status, assetId, zoneId, cursor, bounded(limit, 500));
    }

    /** Returns one alert by identifier. */
    @GetMapping("/api/v1/alerts/{alertId}")
    Object alert(HttpServletRequest request, @PathVariable String alertId) {
        operator(request);
        return queries.alert(alertId);
    }

    /** Returns a cursor-based page of an alert's audit trail. */
    @GetMapping("/api/v1/alerts/{alertId}/audit")
    Object audit(HttpServletRequest request, @PathVariable String alertId, @RequestParam(defaultValue="0") long cursor,
                 @RequestParam(defaultValue="5000") int limit) {
        operator(request);
        if (cursor < 0) throw Problem.validation();
        return queries.audit(alertId, cursor, bounded(limit, 5000));
    }

    /** Applies an authorized lifecycle command to an alert. */
    @PostMapping("/api/v1/alerts/{alertId}/{action:claim|release|resolve}")
    Object command(HttpServletRequest request, @PathVariable String alertId, @PathVariable String action,
                   @RequestBody(required=false) JsonNode body) {
        Auth.User caller = operator(request);
        String note = body != null && body.path("note").isTextual() ? body.path("note").textValue() : null;
        return queries.command(alertId, action, caller, note);
    }

    /** Requires an operator or supervisor identity for a request. */
    private Auth.User operator(HttpServletRequest request) {
        return auth.require(request.getHeader("Authorization"), "OPERATOR", "SUPERVISOR");
    }

    /** Validates that a requested page size is within its endpoint limit. */
    private static int bounded(int value, int max) {
        if (value < 1 || value > max) throw Problem.validation();
        return value;
    }
}