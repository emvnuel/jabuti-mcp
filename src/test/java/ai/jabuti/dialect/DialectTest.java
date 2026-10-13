package ai.jabuti.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Dialect resolution and the per-engine behavior matrix. Behavior tests
 * instantiate the package-private dialects directly; resolution goes through
 * {@link Dialects}.
 */
class DialectTest {

    private static final Supplier<String> LAZY_FAILURE = () -> {
        throw new AssertionError("current catalog must only be read for catalog-based engines");
    };

    @Test
    void resolvesDialectFromJdbcUrl() {
        assertEquals("PG", Dialects.fromJdbcUrl("jdbc:postgresql://localhost/db").id());
        assertEquals("MYSQL", Dialects.fromJdbcUrl("jdbc:mysql://localhost/db").id());
        assertEquals("MARIADB", Dialects.fromJdbcUrl("jdbc:mariadb://localhost/db").id());
        assertEquals("SQLITE", Dialects.fromJdbcUrl("jdbc:sqlite:/tmp/x.db").id());
        assertEquals("ORACLE", Dialects.fromJdbcUrl("jdbc:oracle:thin:@localhost:1521/XE").id());
        assertEquals("DB2", Dialects.fromJdbcUrl("jdbc:db2://localhost:50000/app").id());
        assertEquals("DB2", Dialects.fromJdbcUrl("jdbc:as400://AS400/app").id());
        assertEquals("DERBY", Dialects.fromJdbcUrl("jdbc:derby:memory:app;create=true").id());
        assertEquals("FIREBIRD", Dialects.fromJdbcUrl("jdbc:firebirdsql://localhost/app").id());
        assertEquals("HANA", Dialects.fromJdbcUrl("jdbc:sap://localhost:30015").id());
        assertEquals("HSQLDB", Dialects.fromJdbcUrl("jdbc:hsqldb:mem:app").id());
        assertEquals("INFORMIX", Dialects.fromJdbcUrl("jdbc:informix-sqli://localhost:9088/app").id());
        assertEquals("OTHER", Dialects.fromJdbcUrl("jdbc:unknown://localhost/db").id());
        assertEquals("OTHER", Dialects.fromJdbcUrl(null).id());
    }

    @Test
    void resolutionIgnoresUrlCase() {
        assertEquals("PG", Dialects.fromJdbcUrl("JDBC:PostgreSQL://localhost/db").id());
    }

    @Test
    void builtInDialectIdsAreDistinct() {
        Set<String> ids = new HashSet<>();
        Dialects.builtIn().forEach(dialect -> assertTrue(ids.add(dialect.id()),
                "duplicate dialect id " + dialect.id()));
    }

    @Test
    void everyPermittedDialectIsRegistered() {
        Set<Class<?>> declared = new HashSet<>();
        collectPermitted(Dialect.class, declared);
        declared.remove(Dialects.fallback().getClass());

        Set<Class<?>> registered = Dialects.builtIn().stream()
                .map(Dialect::getClass)
                .collect(Collectors.toSet());

        assertEquals(declared, registered, "every dialect class must be registered in Dialects");
    }

    private static void collectPermitted(Class<?> dialectClass, Set<Class<?>> collected) {
        for (Class<?> permitted : dialectClass.getPermittedSubclasses()) {
            collected.add(permitted);
            if (permitted.isSealed()) {
                collectPermitted(permitted, collected);
            }
        }
    }

    @Test
    void theFallbackMatchesNothing() {
        assertFalse(Dialects.fallback().matches("jdbc:postgresql://localhost/db"));
        assertFalse(Dialects.fallback().matches(null));
    }

    @Test
    void probesDummyTableOnlyWhereFromlessSelectIsRejected() {
        assertEquals("SELECT 1", new PostgresDialect().probeSql());
        assertEquals("SELECT 1", new MySqlDialect().probeSql());
        assertEquals("SELECT 1", new MariaDbDialect().probeSql());
        assertEquals("SELECT 1", new SqliteDialect().probeSql());
        assertEquals("SELECT 1", new AnsiDialect().probeSql());
        assertEquals("SELECT 1 FROM DUAL", new OracleDialect().probeSql());
        assertEquals("SELECT 1 FROM SYSIBM.SYSDUMMY1", new Db2Dialect().probeSql());
        assertEquals("SELECT 1 FROM SYSIBM.SYSDUMMY1", new DerbyDialect().probeSql());
        assertEquals("SELECT 1 FROM RDB$DATABASE", new FirebirdDialect().probeSql());
        assertEquals("SELECT 1 FROM DUMMY", new HanaDialect().probeSql());
        assertEquals("SELECT 1 FROM (VALUES(1)) AS dual(dual)", new HsqldbDialect().probeSql());
        assertEquals("SELECT 1 FROM systables WHERE tabid = 1", new InformixDialect().probeSql());
    }

    @Test
    void derivesBackslashEscapesFromTheDialect() {
        assertTrue(new MySqlDialect().backslashEscapedStrings());
        assertTrue(new MariaDbDialect().backslashEscapedStrings());
        assertFalse(new PostgresDialect().backslashEscapedStrings());
        assertFalse(new AnsiDialect().backslashEscapedStrings());
    }

    @Test
    void quotesIdentifiersWithTheEngineDelimiter() {
        assertEquals("\"a\"\"b\"", new PostgresDialect().quote("a\"b"));
        assertEquals("`a``b`", new MySqlDialect().quote("a`b"));
        assertEquals("`orders`", new MariaDbDialect().quote("orders"));
        assertEquals("\"orders\"", new Db2Dialect().quote("orders"));
    }

    @Test
    void qualifiesTablesWithTheQuotedSchema() {
        assertEquals("\"app\".\"orders\"", new PostgresDialect().qualify("app", "orders"));
        assertEquals("`app`.`orders`", new MySqlDialect().qualify("app", "orders"));
        assertEquals("`orders`", new MySqlDialect().qualify(null, "orders"));
    }

    @Test
    void buildsExplainForTheEnginesThatSupportIt() {
        assertEquals("EXPLAIN SELECT 1", new PostgresDialect().explain("SELECT 1"));
        assertEquals("EXPLAIN SELECT 1", new MySqlDialect().explain("SELECT 1"));
        assertEquals("EXPLAIN SELECT 1", new MariaDbDialect().explain("SELECT 1"));
        assertEquals("EXPLAIN QUERY PLAN SELECT 1", new SqliteDialect().explain("SELECT 1"));
    }

    @Test
    void supportsExplainForKnownEnginesOnly() {
        assertTrue(new PostgresDialect().supportsExplain());
        assertTrue(new MySqlDialect().supportsExplain());
        assertTrue(new MariaDbDialect().supportsExplain());
        assertTrue(new SqliteDialect().supportsExplain());
        assertFalse(new OracleDialect().supportsExplain());
        assertFalse(new Db2Dialect().supportsExplain());
        assertFalse(new DerbyDialect().supportsExplain());
        assertFalse(new HsqldbDialect().supportsExplain());
        assertFalse(new AnsiDialect().supportsExplain());
    }

    @Test
    void unsupportedExplainFailsFast() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> new Db2Dialect().explain("SELECT 1"));
        assertTrue(error.getMessage().contains("DB2"), error.getMessage());
    }

    @Test
    void pgScopePrefersPublic() {
        assertEquals(List.of("public"), new PostgresDialect().defaultSchemaScope(List.of("app", "public"), LAZY_FAILURE));
    }

    @Test
    void pgScopeFallsBackToAllSchemasWithoutPublic() {
        assertEquals(List.of("app"), new PostgresDialect().defaultSchemaScope(List.of("app"), LAZY_FAILURE));
    }

    @Test
    void catalogEnginesUseCurrentCatalog() {
        assertEquals(List.of("app"), new MySqlDialect().defaultSchemaScope(List.of("app"), () -> "app"));
        assertEquals(List.of("app"), new MariaDbDialect().defaultSchemaScope(List.of("app"), () -> "app"));
    }

    @Test
    void catalogEnginesFallBackWhenCatalogMissing() {
        assertEquals(List.of("app", "other"), new MySqlDialect().defaultSchemaScope(List.of("app", "other"), () -> null));
    }

    @Test
    void schemalessEnginesReadWithoutFilter() {
        assertEquals(Collections.singletonList(null), new AnsiDialect().defaultSchemaScope(List.of(), LAZY_FAILURE));
        assertEquals(Collections.singletonList(null), new SqliteDialect().defaultSchemaScope(List.of(), LAZY_FAILURE));
    }

    @Test
    void otherEnginesSearchAllSchemas() {
        assertEquals(List.of("A", "B"), new AnsiDialect().defaultSchemaScope(List.of("A", "B"), LAZY_FAILURE));
    }

    @Test
    void sqliteIsPinnedToOneConnection() {
        assertEquals(1, new SqliteDialect().effectiveMaxPoolSize(10));
        assertEquals(10, new PostgresDialect().effectiveMaxPoolSize(10));
        assertEquals(10, new AnsiDialect().effectiveMaxPoolSize(10));
    }
}
