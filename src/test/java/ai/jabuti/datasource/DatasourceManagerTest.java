package ai.jabuti.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import ai.jabuti.config.JdbcMcpConfig;
import ai.jabuti.config.SourceEntry;
import ai.jabuti.testsupport.H2Sources;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DatasourceManagerTest {

    private DatasourceManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void connectsAndProbesWithSelectOne() throws Exception {
        manager = H2Sources.uniqueManager();

        manager.ensureConnected("h2db");

        HikariDataSource pool = manager.poolFor("h2db");
        assertFalse(pool.isClosed());
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    @Test
    void rejectsUnknownSourceListingKnownOnes() {
        manager = H2Sources.uniqueManager();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.ensureConnected("ghost"));
        assertTrue(error.getMessage().contains("Unknown source 'ghost'"), error.getMessage());
        assertTrue(error.getMessage().contains("h2db"), error.getMessage());
    }

    @Test
    void connectsToDb2WithItsDummyTableProbe() {
        List<String> executed = new CopyOnWriteArrayList<>();
        manager = recordingManager("jdbc:db2://localhost:50000/app", executed,
                Set.of("SELECT 1 FROM SYSIBM.SYSDUMMY1"));

        manager.ensureConnected("db");

        assertEquals(List.of("SELECT 1 FROM SYSIBM.SYSDUMMY1"), executed);
    }

    @Test
    void probesHealthWithTheDialectStatement() {
        List<String> executed = new CopyOnWriteArrayList<>();
        manager = recordingManager("jdbc:oracle:thin:@localhost:1521/XE", executed, Set.of("SELECT 1 FROM DUAL"));

        DatasourceManager.DatabaseInfo info = manager.databaseInfo("db");

        assertTrue(info.selectOneOk());
        assertEquals("RecordingDB", info.product());
        assertTrue(info.notes().isEmpty(), info.notes().toString());
        assertEquals(List.of("SELECT 1 FROM DUAL"), executed);
    }

    @Test
    void failedProbeDropsThePoolForRetry() {
        List<String> executed = new CopyOnWriteArrayList<>();
        manager = recordingManager("jdbc:db2://localhost:50000/app", executed, Set.of());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> manager.ensureConnected("db"));

        assertTrue(error.getMessage().contains("Unable to connect to source 'db'"), error.getMessage());
        assertEquals(List.of("SELECT 1 FROM SYSIBM.SYSDUMMY1"), executed);
        assertFalse(manager.isConnected("db"));
    }

    @Test
    void failedHealthProbeDegradesToANote() {
        List<String> executed = new CopyOnWriteArrayList<>();
        manager = recordingManager("jdbc:db2://localhost:50000/app", executed, Set.of());

        DatasourceManager.DatabaseInfo info = manager.databaseInfo("db");

        assertFalse(info.selectOneOk());
        assertTrue(info.notes().stream().anyMatch(note -> note.contains("SELECT 1 FROM SYSIBM.SYSDUMMY1")),
                info.notes().toString());
    }

    @Test
    void failsTheConnectionWhenTheProbeReturnsNoRows() {
        List<String> executed = new CopyOnWriteArrayList<>();
        manager = recordingManager("jdbc:db2://localhost:50000/app", executed,
                Set.of("SELECT 1 FROM SYSIBM.SYSDUMMY1"), false);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> manager.ensureConnected("db"));

        assertTrue(error.getMessage().contains("probe returned no rows"), error.getMessage());
        assertFalse(manager.isConnected("db"));
    }

    @Test
    void emptyProbeDegradesToANoteInHealthCheck() {
        List<String> executed = new CopyOnWriteArrayList<>();
        manager = recordingManager("jdbc:db2://localhost:50000/app", executed,
                Set.of("SELECT 1 FROM SYSIBM.SYSDUMMY1"), false);

        DatasourceManager.DatabaseInfo info = manager.databaseInfo("db");

        assertFalse(info.selectOneOk());
        assertTrue(info.notes().stream().anyMatch(note -> note.contains("probe returned no rows")),
                info.notes().toString());
    }

    /**
     * A manager over a recording driver whose fake server accepts exactly the
     * statements in {@code accepted} and returns one row for every accepted
     * query; used to pin probe selection for engines that cannot run embedded
     * in tests.
     */
    private static DatasourceManager recordingManager(String url, List<String> executed, Set<String> accepted) {
        return recordingManager(url, executed, accepted, true);
    }

    /**
     * Same as {@link #recordingManager(String, List, Set)}, but queries return
     * either one row or none, to exercise empty probe results.
     */
    private static DatasourceManager recordingManager(String url, List<String> executed, Set<String> accepted,
            boolean probeReturnsRow) {
        JdbcMcpConfig config = new JdbcMcpConfig(
                List.of(new SourceEntry("db", url, "u", "s3cret", "recording-jdbc", null)),
                List.of(),
                List.of(),
                List.of());
        return new DatasourceManager(config, driverId -> new RecordingDriver(executed, accepted, probeReturnsRow));
    }

    private record RecordingDriver(List<String> executed, Set<String> accepted, boolean probeReturnsRow)
            implements Driver {

        @Override
        public Connection connect(String url, Properties info) {
            return connection(executed, accepted, probeReturnsRow);
        }

        @Override
        public boolean acceptsURL(String url) {
            return true;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }

    private static Connection connection(List<String> executed, Set<String> accepted, boolean probeReturnsRow) {
        return proxy(Connection.class, (self, method, args) -> switch (method.getName()) {
            case "createStatement" -> statement(executed, accepted, probeReturnsRow);
            case "getMetaData" -> metadata();
            case "isValid" -> true;
            case "isClosed" -> false;
            case "getAutoCommit" -> true;
            default -> null;
        });
    }

    private static Statement statement(List<String> executed, Set<String> accepted, boolean probeReturnsRow) {
        return proxy(Statement.class, (self, method, args) -> switch (method.getName()) {
            case "execute", "executeQuery" -> {
                String sql = (String) args[0];
                executed.add(sql);
                if (!accepted.contains(sql)) {
                    throw new SQLException("statement rejected: " + sql);
                }
                yield "executeQuery".equals(method.getName()) ? resultSet(probeReturnsRow) : true;
            }
            case "isClosed" -> false;
            default -> null;
        });
    }

    private static ResultSet resultSet(boolean returnsRow) {
        AtomicBoolean first = new AtomicBoolean(true);
        return proxy(ResultSet.class, (self, method, args) -> switch (method.getName()) {
            case "next" -> returnsRow && first.getAndSet(false);
            case "getInt" -> 1;
            case "wasNull" -> false;
            default -> null;
        });
    }

    private static DatabaseMetaData metadata() {
        return proxy(DatabaseMetaData.class, (self, method, args) -> switch (method.getName()) {
            case "getDatabaseProductName" -> "RecordingDB";
            case "getDatabaseProductVersion" -> "1.0";
            default -> null;
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(DatasourceManagerTest.class.getClassLoader(),
                new Class<?>[] {type}, (self, method, args) -> {
                    return switch (method.getName()) {
                        case "equals" -> self == args[0];
                        case "hashCode" -> System.identityHashCode(self);
                        case "toString" -> "recording " + type.getSimpleName();
                        default -> {
                            Object result = handler.invoke(self, method, args);
                            yield result != null ? result : defaultValue(method.getReturnType());
                        }
                    };
                }));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }
}
