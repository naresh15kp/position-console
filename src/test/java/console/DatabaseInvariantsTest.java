package console;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies database constraints and triggers that protect cross-service invariants. */
class DatabaseInvariantsTest {
    private Connection db;

    /** Opens a rollback-only integration connection when test credentials are available. */
    @BeforeEach
    void connect() throws SQLException {
        String url = System.getenv("IT_DB_URL");
        Assumptions.assumeTrue(url != null, "IT_DB_URL enables PostgreSQL invariant tests");
        db = DriverManager.getConnection(
                url,
                System.getenv().getOrDefault("IT_DB_USER", ""),
                System.getenv().getOrDefault("IT_DB_PASSWORD", "")
        );
        db.setAutoCommit(false);
    }

    /** Rolls back each integration fixture and closes its connection. */
    @AfterEach
    void close() throws SQLException {
        if (db != null) {
            db.rollback();
            db.close();
        }
    }

    /** Verifies active-alert uniqueness and transactional audit rollback. */
    @Test
    void enforcesActiveAlertUniquenessAndRollsBackAuditWithState() throws SQLException {
        Fixture fixture = fixture();
        long alertId;
        try (PreparedStatement statement = db.prepareStatement(
                "insert into alerts(asset_id,zone_id,trigger_report_id) values (?,?,?) returning id")) {
            statement.setString(1, fixture.asset());
            statement.setString(2, "Z-01");
            statement.setString(3, fixture.report());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                alertId = result.getLong(1);
            }
        }

        try (PreparedStatement actor = db.prepareStatement("select set_config('console.actor',?,true)")) {
            actor.setString(1, "operator");
            actor.execute();
        }
        try (PreparedStatement claim = db.prepareStatement("""
                update alerts set status='CLAIMED',claimed_by='operator',claimed_at=clock_timestamp()
                where id=?
                """)) {
            claim.setLong(1, alertId);
            assertThat(claim.executeUpdate()).isEqualTo(1);
        }
        assertThat(count("select count(*) from alert_audit where alert_id=" + alertId)).isEqualTo(2);

        assertThatThrownBy(() -> {
            try (PreparedStatement duplicate = db.prepareStatement(
                    "insert into alerts(asset_id,zone_id,trigger_report_id) values (?,?,?)")) {
                duplicate.setString(1, fixture.asset());
                duplicate.setString(2, "Z-01");
                duplicate.setString(3, fixture.report());
                duplicate.executeUpdate();
            }
        }).isInstanceOf(SQLException.class);
        db.rollback();

        assertThat(count("select count(*) from assets where asset_id='" + fixture.asset() + "'")).isZero();
        assertThat(count("select count(*) from alert_audit where alert_id=" + alertId)).isZero();
    }

    /** Verifies that an asset cannot reference another asset's report. */
    @Test
    void rejectsCurrentReportFromAnotherAsset() throws SQLException {
        Fixture first = fixture();
        Fixture second = fixture();
        try (PreparedStatement update = db.prepareStatement(
                "update assets set current_report_id=?,current_observed_at=now(),current_observed_ns=0,lat=0,lon=0 where asset_id=?")) {
            update.setString(1, second.report());
            update.setString(2, first.asset());
            update.executeUpdate();
        }
        assertThatThrownBy(() -> {
            try (Statement statement = db.createStatement()) {
                statement.execute("set constraints asset_current_report_matches immediate");
            }
        }).isInstanceOf(SQLException.class);
    }

    /** Verifies that persisted audit rows cannot be changed. */
    @Test
    void rejectsAuditMutation() throws SQLException {
        Fixture fixture = fixture();
        long auditId;
        try (PreparedStatement alert = db.prepareStatement(
                "insert into alerts(asset_id,zone_id,trigger_report_id) values (?,?,?)")) {
            alert.setString(1, fixture.asset());
            alert.setString(2, "Z-01");
            alert.setString(3, fixture.report());
            alert.executeUpdate();
        }
        try (Statement statement = db.createStatement();
             ResultSet result = statement.executeQuery("select max(id) from alert_audit")) {
            result.next();
            auditId = result.getLong(1);
        }
        assertThatThrownBy(() -> {
            try (PreparedStatement update = db.prepareStatement("update alert_audit set actor='changed' where id=?")) {
                update.setLong(1, auditId);
                update.executeUpdate();
            }
        }).isInstanceOf(SQLException.class);
    }

    /** Inserts a unique asset and report fixture in the current transaction. */
    private Fixture fixture() throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String asset = "DB-" + suffix.substring(0, 12).toUpperCase();
        String report = suffix.substring(0, 16);
        try (PreparedStatement statement = db.prepareStatement("insert into assets(asset_id) values (?)")) {
            statement.setString(1, asset);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = db.prepareStatement(
                "insert into reports(report_id,asset_id,observed_at,observed_ns,lat,lon) values (?,?,now(),0,0,0)")) {
            statement.setString(1, report);
            statement.setString(2, asset);
            statement.executeUpdate();
        }
        return new Fixture(asset, report);
    }

    /** Executes a scalar count query for an assertion. */
    private long count(String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    /** Identifies the asset and report created for an invariant test. */
    private record Fixture(String asset, String report) {}
}
