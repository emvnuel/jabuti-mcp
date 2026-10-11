package ai.jabuti.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.testsupport.H2Sources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetadataServiceTest {

    private DatasourceManager datasources;
    private MetadataService metadata;

    @BeforeEach
    void setUp() throws Exception {
        datasources = H2Sources.uniqueManager();
        metadata = new MetadataService(datasources);
        datasources.ensureConnected("h2db");
        try (Connection connection = datasources.poolFor("h2db").getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers ("
                    + "id INT PRIMARY KEY, "
                    + "name VARCHAR(50) NOT NULL DEFAULT 'unknown')");
            statement.execute("CREATE INDEX idx_customers_id ON customers (id)");
            statement.execute("CREATE TABLE orders ("
                    + "id INT PRIMARY KEY, "
                    + "customer_id INT, "
                    + "amount DECIMAL(10,2))");
            statement.execute("CREATE UNIQUE INDEX idx_orders_customer ON orders (customer_id)");
            statement.execute("CREATE VIEW active_customers AS SELECT id, name FROM customers");
        }
    }

    @AfterEach
    void tearDown() {
        datasources.close();
    }

    @Test
    void listsSchemas() {
        List<String> schemas = metadata.getSchemas("h2db");
        assertTrue(schemas.contains("PUBLIC"), schemas.toString());
    }

    @Test
    void listsTablesAndViews() {
        List<String> tables = metadata.getTables("h2db", "PUBLIC");
        assertTrue(tables.containsAll(List.of("CUSTOMERS", "ORDERS")), tables.toString());
        assertFalse(tables.contains("ACTIVE_CUSTOMERS"), "views must not be listed as tables");

        List<String> views = metadata.getViews("h2db", "PUBLIC");
        assertTrue(views.contains("ACTIVE_CUSTOMERS"), views.toString());
        assertFalse(views.contains("CUSTOMERS"));
    }

    @Test
    void listsColumnsInOrdinalOrder() {
        List<ColumnInfo> columns = metadata.getColumns("h2db", "PUBLIC", "CUSTOMERS");

        assertEquals(2, columns.size());
        assertEquals("ID", columns.get(0).column_name());
        assertEquals("NAME", columns.get(1).column_name());
        assertEquals("NO", columns.get(1).is_nullable());
        assertEquals("'unknown'", columns.get(1).column_default());
        assertTrue(columns.get(1).data_type().toUpperCase().contains("CHAR"), columns.get(1).data_type());
    }

    @Test
    void listsIndexesWithPrimaryKeyAndUniqueness() {
        List<IndexInfo> indexes = metadata.getIndexes("h2db", "PUBLIC", "ORDERS");

        IndexInfo primary = indexes.stream()
                .filter(index -> index.is_primary())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no primary index found: " + indexes));
        assertEquals(List.of("ID"), primary.column_names());

        IndexInfo unique = indexes.stream()
                .filter(index -> index.index_name().equalsIgnoreCase("idx_orders_customer"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("unique index not found: " + indexes));
        assertTrue(unique.is_unique());
        assertFalse(unique.is_primary());
        assertEquals(List.of("CUSTOMER_ID"), unique.column_names());
    }

    @Test
    void secondaryIndexOnPrimaryKeyColumnsIsNotMarkedPrimary() {
        List<IndexInfo> indexes = metadata.getIndexes("h2db", "PUBLIC", "CUSTOMERS");

        IndexInfo secondary = indexes.stream()
                .filter(index -> index.index_name().equalsIgnoreCase("idx_customers_id"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("secondary index not found: " + indexes));
        assertFalse(secondary.is_primary(), secondary.toString());
        assertTrue(indexes.stream().anyMatch(IndexInfo::is_primary), indexes.toString());
    }
}
