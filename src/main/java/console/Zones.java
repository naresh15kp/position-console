package console;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.*;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
class Zones {
    private static final Logger LOG = LoggerFactory.getLogger(Zones.class);
    private final GeometryFactory geometry = new GeometryFactory();
    private final Map<String, List<PreparedGeometry>> restricted = new LinkedHashMap<>();
    private final Map<String, List<PreparedGeometry>> all = new LinkedHashMap<>();
    final JsonNode display;

    Zones(ObjectMapper mapper) throws Exception {
        try (var input = getClass().getResourceAsStream("/zones.geojson")) { display = mapper.readTree(input); }
        Set<String> ids = new HashSet<>();
        List<JsonNode> omitted = new ArrayList<>();
        for (JsonNode feature : display.path("features")) {
            JsonNode props = feature.path("properties");
            String id = props.path("zoneId").asText();
            JsonNode shape = feature.path("geometry");
            try {
                List<PreparedGeometry> polygons = new ArrayList<>();
                JsonNode points = shape.path("coordinates");
                switch (shape.path("type").asText()) {
                    case "Polygon" -> polygons.add(prepare(points));
                    case "MultiPolygon" -> { for (JsonNode polygon : points) polygons.add(prepare(polygon)); }
                    default -> throw new IllegalArgumentException("Unsupported zone geometry");
                }
                if (id.isBlank() || polygons.isEmpty() || !ids.add(id)) throw new IllegalArgumentException("Invalid zone ID or geometry");
                all.put(id, List.copyOf(polygons));
                if (props.path("class").asText().equals("RESTRICTED")) restricted.put(id, List.copyOf(polygons));
            } catch (IllegalArgumentException e) {
                omitted.add(feature);
                LOG.warn("Skipped invalid zone {}", id);
            }
        }
        ArrayNode features = (ArrayNode) display.path("features");
        for (int i = features.size() - 1; i >= 0; i--) if (omitted.contains(features.get(i))) features.remove(i);
    }

    Set<String> inside(double lon, double lat) {
        return inside(lon, lat, restricted);
    }

    Set<String> membership(double lon, double lat) {
        return inside(lon, lat, all);
    }

    private Set<String> inside(double lon, double lat, Map<String, List<PreparedGeometry>> zones) {
        Set<String> result = new LinkedHashSet<>();
        Point point = geometry.createPoint(new Coordinate(lon, lat));
        Point east = geometry.createPoint(new Coordinate(lon + 360, lat));
        Point west = geometry.createPoint(new Coordinate(lon - 360, lat));
        zones.forEach((id, parts) -> {
            if (parts.stream().anyMatch(part -> part.contains(point) || part.contains(east) || part.contains(west))) result.add(id);
        });
        return result;
    }

    private PreparedGeometry prepare(JsonNode rings) {
        if (rings.isEmpty()) throw new IllegalArgumentException("Missing outer ring");
        LinearRing shell = ring(rings.get(0), null);
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 0; i < holes.length; i++) holes[i] = ring(rings.get(i + 1), shell.getCoordinate().x);
        Geometry polygon = geometry.createPolygon(shell, holes);
        if (!polygon.isValid()) polygon = GeometryFixer.fix(polygon);
        if (polygon.isEmpty() || polygon.getDimension() != 2) throw new IllegalArgumentException("Empty zone");
        return PreparedGeometryFactory.prepare(polygon);
    }

    private LinearRing ring(JsonNode points, Double anchor) {
        List<Coordinate> ring = new ArrayList<>();
        double previous = anchor == null ? points.path(0).path(0).asDouble() : anchor;
        for (JsonNode p : points) {
            double x = p.path(0).asDouble(Double.NaN);
            double y = p.path(1).asDouble(Double.NaN);
            if (!(Math.abs(x) <= 180 && Math.abs(y) <= 90)) throw new IllegalArgumentException("Invalid zone coordinates");
            while (x - previous > 180) x -= 360;
            while (x - previous < -180) x += 360;
            var coordinate = new Coordinate(x, y);
            if (ring.isEmpty() || !ring.getLast().equals2D(coordinate)) ring.add(coordinate);
            previous = x;
        }
        if (ring.size() < 3) throw new IllegalArgumentException("Too few vertices");
        if (!ring.getFirst().equals2D(ring.getLast())) ring.add(ring.getFirst().copy());
        return geometry.createLinearRing(ring.toArray(Coordinate[]::new));
    }
}