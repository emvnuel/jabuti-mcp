package ai.jabuti.datasource;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Driver;
import java.util.List;
import java.util.UUID;

import ai.jabuti.config.JdbcMcpConfig;
import ai.jabuti.config.SourceEntry;
import org.junit.jupiter.api.Test;

/**
 * The connection probe against real engines that reject a FROM-less
 * {@code SELECT 1}: HSQLDB and Derby both require their dummy-table form, so
 * a regression to {@code SELECT 1} fails here instead of at a user's server.
 * DB2, Oracle, Firebird, HANA and Informix need a server, so their probe
 * statements stay pinned by {@code DialectTest} and the recording-driver
 * tests in {@code DatasourceManagerTest}.
 */
class EmbeddedEngineProbeTest {

    @Test
    void probesHsqldbWithItsValuesDual() {
        probes("jdbc:hsqldb:mem:probe" + suffix(), new org.hsqldb.jdbc.JDBCDriver());
    }

    @Test
    void probesDerbyWithSysibmSysdummy1() {
        // Keep the engine's log file out of the project root.
        System.setProperty("derby.stream.error.file", "target/derby.log");
        probes("jdbc:derby:memory:probe" + suffix() + ";create=true",
                new org.apache.derby.iapi.jdbc.AutoloadedDriver());
    }

    private static void probes(String url, Driver driver) {
        DatasourceManager manager = manager(url, driver);
        try {
            manager.ensureConnected("db");
            DatasourceManager.DatabaseInfo info = manager.databaseInfo("db");
            assertTrue(info.selectOneOk(), info.notes().toString());
            assertTrue(info.notes().isEmpty(), info.notes().toString());
        } finally {
            manager.close();
        }
    }

    private static DatasourceManager manager(String url, Driver driver) {
        JdbcMcpConfig config = new JdbcMcpConfig(
                List.of(new SourceEntry("db", url, null, null, "test-jdbc", null)),
                List.of(),
                List.of(),
                List.of());
        return new DatasourceManager(config, driverId -> driver);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
