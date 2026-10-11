package ai.jabuti.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.jdbc.MetadataService;
import ai.jabuti.testsupport.H2Sources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The health check's data shape over a live local database.
 */
class HealthCheckHandlerTest {

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
    void reportsPoolProbeAndProduct() {
        Map<String, Object> data = new HealthCheckHandler(datasources).check("h2db");

        assertEquals("h2db", data.get("source_id"));

        Map<?, ?> connections = (Map<?, ?>) data.get("connections");
        assertTrue(connections.keySet().containsAll(List.of("total", "active", "idle", "max")), connections.toString());

        Map<?, ?> database = (Map<?, ?>) data.get("database");
        assertTrue(String.valueOf(database.get("product")).contains("H2"), database.toString());

        Map<?, ?> probe = (Map<?, ?>) data.get("probe");
        assertEquals(Boolean.TRUE, probe.get("select1_ok"));

        assertNull(data.get("bufferCache"));
        assertFalse(((List<?>) data.get("notes")).isEmpty());
    }
}
