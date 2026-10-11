package ai.jabuti.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.jdbc.MetadataService;
import ai.jabuti.jdbc.QueryExecutor;
import ai.jabuti.testsupport.H2Sources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shared search loop over real metadata (H2): pattern filtering, the
 * limit gate, detail gating and the wire shapes of every object type. Tables
 * are created quoted-lowercase so the dialect quoting used for row counts
 * ({@code "PUBLIC"."customers"}) resolves on H2.
 */
class ObjectSearchTest {

    private DatasourceManager datasources;
    private MetadataService metadata;
    private QuietMetadata quiet;
    private ObjectSearch objectSearch;

    @BeforeEach
    void setUp() throws Exception {
        datasources = H2Sources.uniqueManager();
        metadata = new MetadataService(datasources);
        quiet = new QuietMetadata(datasources);
        objectSearch = new ObjectSearch(metadata);
        datasources.ensureConnected("h2db");
        try (Connection connection = datasources.poolFor("h2db").getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"customers\" (id INT PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            statement.execute("CREATE INDEX idx_customers_name ON \"customers\" (name)");
            statement.execute("CREATE VIEW \"active_customers\" AS SELECT id, name FROM \"customers\"");
            statement.execute("INSERT INTO \"customers\" (id, name) VALUES (1, 'a'), (2, 'b')");
        }
    }

    @AfterEach
    void tearDown() {
        datasources.close();
    }

    private List<Map<String, Object>> search(String objectType, String pattern, String schema, String detailLevel,
            String table, ObjectSearcher scanner) {
        return objectSearch.search("h2db",
                SearchRequest.parse(objectType, pattern, schema, table, detailLevel, null), scanner);
    }

    private List<Map<String, Object>> columns(String pattern, String detailLevel) {
        return search("column", pattern, "PUBLIC", detailLevel, null, new ColumnScanner(metadata, quiet));
    }

    private List<Map<String, Object>> indexes(String pattern, String detailLevel) {
        return search("index", pattern, "PUBLIC", detailLevel, null, new IndexScanner(metadata, quiet));
    }

    private List<Map<String, Object>> tables(String pattern, String detailLevel) {
        return search("table", pattern, "PUBLIC", detailLevel, null,
                new TableScanner(metadata, quiet, new QueryExecutor(datasources)));
    }

    private List<Map<String, Object>> views(String pattern, String detailLevel) {
        return search("view", pattern, "PUBLIC", detailLevel, null, new ViewScanner(metadata, quiet));
    }

    private List<Map<String, Object>> schemas(String pattern, String detailLevel) {
        return search("schema", pattern, "PUBLIC", detailLevel, null, new SchemaScanner(metadata));
    }

    // ── column ──────────────────────────────────────────────────────────

    @Test
    void columnNamesDetailCarriesOnlyNameTableSchema() {
        Map<String, Object> id = find(columns("id", "names"), "id", "customers");

        assertEquals("PUBLIC", id.get("schema"));
        assertFalse(id.containsKey("type"), id.toString());
        assertFalse(id.containsKey("nullable"), id.toString());
        assertFalse(id.containsKey("default"), id.toString());
    }

    @Test
    void columnSearchCoversViews() {
        List<Map<String, Object>> results = columns("name", "names");

        assertTrue(results.stream().anyMatch(result -> "active_customers".equals(result.get("table"))),
                results.toString());
    }

    @Test
    void columnSummaryAddsColumnDetails() {
        Map<String, Object> name = find(columns("name", "summary"), "name", "customers");

        assertTrue(name.containsKey("type"), name.toString());
        assertTrue(name.containsKey("nullable"), name.toString());
        assertTrue(name.containsKey("default"), name.toString());
    }

    // ── index ───────────────────────────────────────────────────────────

    @Test
    void indexSearchIgnoresViews() {
        List<Map<String, Object>> results = indexes("%", "names");

        assertTrue(results.stream().noneMatch(result -> "active_customers".equals(result.get("table"))),
                results.toString());
    }

    @Test
    void indexNamesDetailCarriesOnlyNameTableSchema() {
        Map<String, Object> index = find(indexes("idx_customers%", "names"), "IDX_CUSTOMERS_NAME", "customers");

        assertEquals("PUBLIC", index.get("schema"));
        assertFalse(index.containsKey("columns"), index.toString());
        assertFalse(index.containsKey("unique"), index.toString());
        assertFalse(index.containsKey("primary"), index.toString());
    }

    @Test
    void indexSummaryAddsIndexDetails() {
        Map<String, Object> index = find(indexes("idx_customers%", "summary"), "IDX_CUSTOMERS_NAME", "customers");

        assertEquals(List.of("NAME"), index.get("columns"));
        assertEquals(Boolean.FALSE, index.get("unique"));
        assertEquals(Boolean.FALSE, index.get("primary"));
    }

    @Test
    void indexSummaryMarksPrimaryIndex() {
        List<Map<String, Object>> results = indexes("%", "summary");

        assertTrue(results.stream().anyMatch(result -> Boolean.TRUE.equals(result.get("primary"))),
                results.toString());
    }

    // ── table ───────────────────────────────────────────────────────────

    @Test
    void tableNamesDetailCarriesNameAndSchemaOnly() {
        Map<String, Object> customers = findTable(tables("customers", "names"), "customers");

        assertEquals("PUBLIC", customers.get("schema"));
        assertFalse(customers.containsKey("column_count"), customers.toString());
        assertFalse(customers.containsKey("row_count"), customers.toString());
    }

    @Test
    void tableSummaryAddsCounts() {
        Map<String, Object> customers = findTable(tables("customers", "summary"), "customers");

        assertEquals(2, customers.get("column_count"));
        assertEquals(2, customers.get("row_count"));
    }

    @Test
    void tableFullAddsColumnsIndexesAndRowCount() {
        Map<String, Object> customers = findTable(tables("customers", "full"), "customers");

        assertEquals(2, customers.get("row_count"));
        List<Map<String, Object>> columnPayloads = payloads(customers.get("columns"));
        assertTrue(columnPayloads.stream()
                        .anyMatch(column -> "id".equalsIgnoreCase(String.valueOf(column.get("name")))),
                columnPayloads.toString());
        List<Map<String, Object>> indexPayloads = payloads(customers.get("indexes"));
        assertTrue(indexPayloads.stream().anyMatch(index -> Boolean.TRUE.equals(index.get("primary"))),
                indexPayloads.toString());
    }

    // ── view ────────────────────────────────────────────────────────────

    @Test
    void viewFullAddsColumnsAndEmptyIndexes() {
        Map<String, Object> view = findTable(views("active_customers", "full"), "active_customers");

        assertEquals(2, view.get("column_count"));
        assertEquals(2, payloads(view.get("columns")).size());
        assertEquals(List.of(), view.get("indexes"));
    }

    @Test
    void viewSummaryAddsColumnCountOnly() {
        Map<String, Object> view = findTable(views("active_customers", "summary"), "active_customers");

        assertEquals(2, view.get("column_count"));
        assertFalse(view.containsKey("columns"), view.toString());
    }

    // ── schema ──────────────────────────────────────────────────────────

    @Test
    void schemaNamesDetailOmitsTableCount() {
        List<Map<String, Object>> results = schemas("PUBLIC", "names");

        assertEquals(1, results.size());
        assertEquals("PUBLIC", results.get(0).get("name"));
        assertFalse(results.get(0).containsKey("table_count"), results.toString());
    }

    @Test
    void schemaSummaryIncludesTableCount() {
        List<Map<String, Object>> results = schemas("PUBLIC", "summary");

        assertEquals(1, results.size());
        assertEquals(1, results.get(0).get("table_count"));
    }

    // ── loop behaviour ──────────────────────────────────────────────────

    @Test
    void patternFiltersCaseInsensitively() {
        assertEquals(1, tables("CUSTOM%", "names").size());
        assertTrue(tables("zzz%", "names").isEmpty());
    }

    @Test
    void limitCapsResults() {
        List<Map<String, Object>> results = objectSearch.search("h2db",
                SearchRequest.parse("index", "%", "PUBLIC", null, "names", 1), new IndexScanner(metadata, quiet));

        assertEquals(1, results.size());
    }

    @Test
    void tableFilterRestrictsTableScopedScans() {
        List<Map<String, Object>> results = search("column", "%", "PUBLIC", "names", "customers",
                new ColumnScanner(metadata, quiet));

        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(result -> "customers".equals(result.get("table"))), results.toString());
    }

    private static Map<String, Object> find(List<Map<String, Object>> results, String name, String table) {
        return results.stream()
                .filter(result -> name.equalsIgnoreCase(String.valueOf(result.get("name")))
                        && table.equalsIgnoreCase(String.valueOf(result.get("table"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no result '" + name + "' on '" + table + "' in " + results));
    }

    private static Map<String, Object> findTable(List<Map<String, Object>> results, String name) {
        return results.stream()
                .filter(result -> name.equalsIgnoreCase(String.valueOf(result.get("name"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no result '" + name + "' in " + results));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> payloads(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
