package ai.jabuti.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
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
                                () -> Map.of("column_count", 2)))),
                new FakeScanner("column", List.of(
                        new ObjectSearcher.ObjectItem("id", Map.of("schema", "PUBLIC", "table", "items"),
                                () -> Map.of())))));
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
    void tableFilterWithoutSchemaDispatches() {
        Map<String, Object> data = handler.search("h2db", "column", null, null, "items", null, null);

        assertEquals("column", data.get("object_type"));
        assertEquals("items", data.get("table"));
        assertNull(data.get("schema"));
        assertTrue((Integer) data.get("count") >= 1, data.toString());
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
        assertTrue(error.getMessage().startsWith("Schema 'nope' does not exist. Available schemas: "),
                error.getMessage());
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

    @Test
    void schemaResolutionHelper() {
        assertEquals(SearchObjectsHandler.SchemaResolution.USE_DEFAULT_SCOPE,
                SearchObjectsHandler.resolveSchema(List.of(), "public"));
        assertEquals(SearchObjectsHandler.SchemaResolution.USE_REQUESTED,
                SearchObjectsHandler.resolveSchema(List.of("PUBLIC", "INFORMATION_SCHEMA"), "PUBLIC"));
        assertEquals(SearchObjectsHandler.SchemaResolution.SCHEMA_NOT_FOUND,
                SearchObjectsHandler.resolveSchema(List.of("PUBLIC", "INFORMATION_SCHEMA"), "nope"));
    }

    @Test
    void requestedSchemaIgnoredOnSchemelessEngine() {
        SearchObjectsHandler schemaless = schemalessHandler();

        Map<String, Object> data = schemaless.search("h2db", "table", null, "public", null, null, null);

        assertNull(data.get("schema"), data.toString());
        assertTrue((Integer) data.get("count") >= 1, data.toString());
        List<?> notes = (List<?>) data.get("notes");
        assertTrue(notes != null
                && notes.contains("database has no schema concept; 'schema' parameter ignored"),
                data.toString());
    }

    @Test
    void schemaObjectTypeOnSchemelessEngineExplainsEmpty() {
        SearchObjectsHandler schemaless = schemalessHandler();

        Map<String, Object> data = schemaless.search("h2db", "schema", null, null, null, null, null);

        assertEquals(0, data.get("count"));
        assertEquals(List.of(), data.get("results"));
        List<?> notes = (List<?>) data.get("notes");
        assertTrue(notes != null && notes.stream().anyMatch(note -> note.toString().contains("no schema concept")),
                data.toString());
    }

    private SearchObjectsHandler schemalessHandler() {
        MetadataService schemaless = new MetadataService(datasources) {
            @Override
            public List<String> getSchemas(String sourceId) {
                return List.of();
            }

            @Override
            public List<String> defaultSchemas(String sourceId) {
                return Collections.singletonList(null);
            }
        };
        return new SearchObjectsHandler(datasources, schemaless, new ObjectSearch(schemaless), List.of(
                new FakeScanner("table", List.of(
                        new ObjectSearcher.ObjectItem("t", Map.of(), () -> Map.of()))),
                new SchemaScanner(schemaless)));
    }

    private record FakeScanner(String objectType, List<ObjectSearcher.ObjectItem> items)
            implements ObjectSearcher {

        @Override
        public Iterable<ObjectItem> items(String sourceId, SearchRequest request, String schema) {
            return items;
        }
    }
}
