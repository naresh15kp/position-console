package console;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies zone loading and geometric membership edge cases. */
class ZonesTest {
    private final Zones zones;

    /** Loads the production zone fixture for each test instance. */
    ZonesTest() throws Exception {
        zones = new Zones(new ObjectMapper());
    }

    /** Verifies holes, multipolygons, overlap, winding, and antimeridian geometry. */
    @Test
    void handlesSuppliedGeometryEdgeCases() {
        assertThat(zones.inside(172.7, 39.2)).contains("Z-03");
        assertThat(zones.inside(173.2, 39.6)).doesNotContain("Z-03");
        assertThat(zones.inside(167.2, 33.2)).contains("Z-06");
        assertThat(zones.inside(168.1, 33.4)).contains("Z-06");
        assertThat(zones.inside(174.8, 32.6)).contains("Z-04", "Z-05");
        assertThat(zones.inside(179.5, 34.5)).contains("Z-08");
        assertThat(zones.inside(-179.5, 34.5)).contains("Z-08");
        assertThat(zones.inside(170.8, 35.3)).contains("Z-02");
    }

    /** Verifies alert boundaries and advisory-zone membership semantics. */
    @Test
    void excludesBoundariesAndAdvisoryZonesFromAlerts() {
        assertThat(zones.inside(168.0, 38.0)).doesNotContain("Z-01");
        assertThat(zones.inside(169.8, 40.8)).doesNotContain("Z-09");
        assertThat(zones.membership(169.8, 40.8)).contains("Z-09");
    }
}
