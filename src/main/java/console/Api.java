package console;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

@RestController
class Api {
    private final Auth auth;
    private final Ingest ingest;
    private final Queries queries;
    private final Zones zones;


    Api(Auth auth, Ingest ingest, Queries queries, Zones zones) {
        this.auth = auth;
        this.ingest = ingest;
        this.queries = queries;
        this.zones = zones;

    }

    @GetMapping("/health")
    Object health() {
        if (!queries.ready()) throw new Problem(503, "INTERNAL_ERROR");
        return Map.of("status", "UP");
    }

    @PostMapping("/api/v1/auth/login")
    Object login(@RequestBody JsonNode body) { return auth.login(body); }

    @PostMapping("/api/v1/reports/batch")
    Object batch(HttpServletRequest request) throws IOException {
        auth.require(request.getHeader("Authorization"), "INGEST");
        return ingest.store(ingest.read(request), MDC.get("requestId"));
    }

    @GetMapping("/api/v1/stats")
    Object stats(HttpServletRequest request) {
        auth.require(request.getHeader("Authorization"));
        return queries.stats();
    }

    @GetMapping("/api/v1/zones")
    Object zones(HttpServletRequest request) {
        operator(request);
        return zones.display;
    }

    @GetMapping("/api/v1/assets")
    Object assets(HttpServletRequest request, @RequestParam String bbox, @RequestParam(defaultValue="1000") int limit) {
        operator(request);
        return queries.assets(bbox, bounded(limit, 5000));
    }

    @GetMapping("/api/v1/assets/{assetId}")
    Object asset(HttpServletRequest request, @PathVariable String assetId) {
        operator(request);
        return queries.asset(assetId);
    }

    @GetMapping("/api/v1/assets/{assetId}/track")
    Object track(HttpServletRequest request, @PathVariable String assetId, @RequestParam(required=false) String from,
                 @RequestParam(required=false) String to, @RequestParam(defaultValue="500") int limit,
                 @RequestParam(defaultValue="false") boolean recent,
                 @RequestParam(required=false) String sourceType) {
        operator(request);
        return queries.track(assetId, from, to, bounded(limit, 5000), recent, sourceType);
    }

    @GetMapping("/api/v1/alerts")
    Object alerts(HttpServletRequest request, @RequestParam(required=false) String status,
                  @RequestParam(required=false) String assetId, @RequestParam(required=false) String zoneId,
                  @RequestParam(required=false) String cursor, @RequestParam(defaultValue="100") int limit) {
        operator(request);
        return queries.alerts(status, assetId, zoneId, cursor, bounded(limit, 500));
    }

    @GetMapping("/api/v1/alerts/{alertId}")
    Object alert(HttpServletRequest request, @PathVariable String alertId) {
        operator(request);
        return queries.alert(alertId);
    }

    @GetMapping("/api/v1/alerts/{alertId}/audit")
    Object audit(HttpServletRequest request, @PathVariable String alertId, @RequestParam(defaultValue="0") long cursor,
                 @RequestParam(defaultValue="5000") int limit) {
        operator(request);
        if (cursor < 0) throw Problem.validation();
        return queries.audit(alertId, cursor, bounded(limit, 5000));
    }

    @PostMapping("/api/v1/alerts/{alertId}/{action:claim|release|resolve}")
    Object command(HttpServletRequest request, @PathVariable String alertId, @PathVariable String action,
                   @RequestBody(required=false) JsonNode body) {
        Auth.User caller = operator(request);
        String note = body != null && body.path("note").isTextual() ? body.path("note").textValue() : null;
        return queries.command(alertId, action, caller, note);
    }

    private Auth.User operator(HttpServletRequest request) {
        return auth.require(request.getHeader("Authorization"), "OPERATOR", "SUPERVISOR");
    }

    private static int bounded(int value, int max) {
        if (value < 1 || value > max) throw Problem.validation();
        return value;
    }
}