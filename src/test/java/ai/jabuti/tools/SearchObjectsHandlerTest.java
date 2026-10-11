package ai.jabuti.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Handler orchestration with fake scanners: request validation, schema
 * scope, the routines note, dispatch and the truncation flag — none of which
 * needed a database before the scanner seam.
 */
class SearchObjectsHandlerTest {

    private DatasourceManager datasources;
    private SearchObjectsHandler handler;

    @BeforeEach
    void setUp() {
        datasources = H2Sources.uniqueManager();
        MetadataService metadata = new MetadataService(datasources);
        ObjectSearch objectSearch = new ObjectSearch(metadata);
        handler = new SearchObjectsHandler(datasources, metadata, objectSearch, List.of(
                new FakeScanner("table", List.of(
                        new ObjectSearcher.ObjectItem("customers", Map.of("schema", "PUBLIC"),
                                () -> Map.of("column_count", 2))))));
    }

    @AfterEach
    void tearDown() {
        datasources.close();
    }

    @Test
    void unsupportedObjectTypeIsRejected() {
        ToolError error = assertThrows(ToolError.class,
                () -> handler.search("h2db", "blob", null, null, null, null, null));

        assertEquals(ToolEnvelope.CODE_INVALID_OBJECT_TYPE, error.code());
    }

    @Test
    void tableFilterRequiresSchema() {
        ToolError error = assertThrows(ToolError.class,
                () -> handler.search("h2db", "column", null, null, "items", null, null));

        assertEquals(ToolEnvelope.CODE_SCHEMA_REQUIRED, error.code());
    }

    @Test
    void tableFilterOnlyAppliesToColumnOrIndex() {
        ToolError error = assertThrows(ToolError.class,
                () -> handler.search("h2db", "table", null, "PUBLIC", "items", null, null));

        assertEquals(ToolEnvelope.CODE_INVALID_TABLE_FILTER, error.code());
    }

    @Test
    void invalidDetailLevelAndLimitAreRejected() {
        assertEquals(ToolEnvelope.CODE_INVALID_INPUT, assertThrows(ToolError.class,
                () -> handler.search("h2db", "table", null, null, null, "partial", null)).code());
        assertEquals(ToolEnvelope.CODE_INVALID_INPUT, assertThrows(ToolError.class,
                () -> handler.search("h2db", "table", null, null, null, null, 0)).code());
        assertEquals(ToolEnvelope.CODE_INVALID_INPUT, assertThrows(ToolError.class,
                () -> handler.search("h2db", "table", null, null, null, null, 1001)).code());
    }

    @Test
    void unknownSchemaIsReported() {
        ToolError error = assertThrows(ToolError.class,
                () -> handler.search("h2db", "table", null, "nope", null, null, null));

        assertEquals(ToolEnvelope.CODE_SCHEMA_NOT_FOUND, error.code());
        assertTrue(error.getMessage().contains("PUBLIC"), error.getMessage());
    }

    @Test
    void routineTypesReturnANoteInsteadOfScanning() {
        Map<String, Object> data = handler.search("h2db", "function", null, null, null, null, null);

        assertEquals(0, data.get("count"));
        assertEquals(List.of(), data.get("results"));
        assertFalse(((List<?>) data.get("notes")).isEmpty());
    }

    @Test
    void dispatchesToItsScannerAndGatesDetailsByLevel() {
        Map<String, Object> names = handler.search("h2db", "table", null, "PUBLIC", null, null, null);

        assertEquals("table", names.get("object_type"));
        assertEquals("%", names.get("pattern"));
        assertEquals("PUBLIC", names.get("schema"));
        assertEquals(1, names.get("count"));
        Map<?, ?> result = (Map<?, ?>) ((List<?>) names.get("results")).get(0);
        assertEquals("customers", result.get("name"));
        assertEquals("PUBLIC", result.get("schema"));
        assertFalse(result.containsKey("column_count"), result.toString());

        Map<String, Object> summary = handler.search("h2db", "table", null, "PUBLIC", null, "summary", null);
        Map<?, ?> detailed = (Map<?, ?>) ((List<?>) summary.get("results")).get(0);
        assertEquals(2, detailed.get("column_count"));
    }

    @Test
    void truncatedWhenResultsReachTheLimit() {
        Map<String, Object> atLimit = handler.search("h2db", "table", null, "PUBLIC", null, null, 1);
        assertEquals(Boolean.TRUE, atLimit.get("truncated"));

        Map<String, Object> belowLimit = handler.search("h2db", "table", null, "PUBLIC", null, null, 2);
        assertEquals(Boolean.FALSE, belowLimit.get("truncated"));
    }

    private record FakeScanner(String objectType, List<ObjectSearcher.ObjectItem> items)
            implements ObjectSearcher {

        @Override
        public Iterable<ObjectItem> items(String sourceId, SearchRequest request, String schema) {
            return items;
        }
    }
}
