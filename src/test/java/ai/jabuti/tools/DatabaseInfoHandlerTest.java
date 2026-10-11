package ai.jabuti.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.testsupport.H2Sources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The database info tool's dialect-hint shape over a live local database.
 */
class DatabaseInfoHandlerTest {

    private DatasourceManager datasources;

    @BeforeEach
    void setUp() {
        datasources = H2Sources.uniqueManager();
    }

    @AfterEach
    void tearDown() {
        datasources.close();
    }

    @Test
    void reportsDialectHintsForH2() {
        Map<String, Object> data = new DatabaseInfoHandler(datasources).info("h2db");

        assertEquals("h2db", data.get("source_id"));
        assertTrue(String.valueOf(data.get("product")).contains("H2"), data.toString());
        assertEquals("\"", data.get("identifier_quote"));
        assertEquals("catalog", data.get("catalog_term"));
        assertEquals("schema", data.get("schema_term"));
        assertEquals(Boolean.FALSE, data.get("read_only"));
        assertEquals(Boolean.TRUE, data.get("supports_transactions"));
        assertNotNull(data.get("sql_keywords"));
        assertFalse(String.valueOf(data.get("notes")).contains("unavailable"),
                "expected no metadata failures on H2: " + data);
    }
}
