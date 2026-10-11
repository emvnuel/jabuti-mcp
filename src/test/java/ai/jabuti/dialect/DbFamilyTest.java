package ai.jabuti.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class DbFamilyTest {

    private static final Supplier<String> LAZY_FAILURE = () -> {
        throw new AssertionError("current catalog must only be read for catalog-based engines");
    };

    @Test
    void derivesFamilyFromJdbcUrl() {
        assertEquals(DbFamily.PG, DbFamily.fromJdbcUrl("jdbc:postgresql://localhost/db"));
        assertEquals(DbFamily.MYSQL, DbFamily.fromJdbcUrl("jdbc:mysql://localhost/db"));
        assertEquals(DbFamily.MARIADB, DbFamily.fromJdbcUrl("jdbc:mariadb://localhost/db"));
        assertEquals(DbFamily.SQLITE, DbFamily.fromJdbcUrl("jdbc:sqlite:/tmp/x.db"));
        assertEquals(DbFamily.OTHER, DbFamily.fromJdbcUrl("jdbc:oracle:thin:@localhost:1521/XE"));
        assertEquals(DbFamily.OTHER, DbFamily.fromJdbcUrl(null));
    }

    @Test
    void buildsDialectExplainStatement() {
        assertEquals("EXPLAIN SELECT 1", DbFamily.PG.explain("SELECT 1"));
        assertEquals("EXPLAIN SELECT 1", DbFamily.MYSQL.explain("SELECT 1"));
        assertEquals("EXPLAIN QUERY PLAN SELECT 1", DbFamily.SQLITE.explain("SELECT 1"));
    }

    @Test
    void supportsExplainForKnownEnginesOnly() {
        assertTrue(DbFamily.PG.supportsExplain());
        assertTrue(DbFamily.MYSQL.supportsExplain());
        assertTrue(DbFamily.MARIADB.supportsExplain());
        assertTrue(DbFamily.SQLITE.supportsExplain());
        assertFalse(DbFamily.OTHER.supportsExplain());
    }

    @Test
    void pgScopePrefersPublic() {
        assertEquals(List.of("public"), DbFamily.PG.defaultSchemaScope(List.of("app", "public"), LAZY_FAILURE));
    }

    @Test
    void pgScopeFallsBackToAllSchemasWithoutPublic() {
        assertEquals(List.of("app"), DbFamily.PG.defaultSchemaScope(List.of("app"), LAZY_FAILURE));
    }

    @Test
    void catalogEnginesUseCurrentCatalog() {
        assertEquals(List.of("app"), DbFamily.MYSQL.defaultSchemaScope(List.of("app"), () -> "app"));
        assertEquals(List.of("app"), DbFamily.MARIADB.defaultSchemaScope(List.of("app"), () -> "app"));
    }

    @Test
    void catalogEnginesFallBackWhenCatalogMissing() {
        assertEquals(List.of("app", "other"), DbFamily.MYSQL.defaultSchemaScope(List.of("app", "other"), () -> null));
    }

    @Test
    void schemalessEnginesReadWithoutFilter() {
        assertEquals(Collections.singletonList(null), DbFamily.OTHER.defaultSchemaScope(List.of(), LAZY_FAILURE));
        assertEquals(Collections.singletonList(null), DbFamily.SQLITE.defaultSchemaScope(List.of(), LAZY_FAILURE));
    }

    @Test
    void otherEnginesSearchAllSchemas() {
        assertEquals(List.of("A", "B"), DbFamily.OTHER.defaultSchemaScope(List.of("A", "B"), LAZY_FAILURE));
    }
}
