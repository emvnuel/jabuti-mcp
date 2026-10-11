package ai.jabuti.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.jdbc.QueryExecutor;
import ai.jabuti.jdbc.StatementParser;
import ai.jabuti.safety.ReadOnlyGuard;
import ai.jabuti.testsupport.H2Sources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code execute_sql} handler as a data producer: payload maps on
 * success, {@link ToolError} codes on the read-only gate.
 */
class ExecuteSqlHandlerTest {

    private DatasourceManager datasources;
    private ExecuteSqlHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        datasources = H2Sources.uniqueManager();
        handler = new ExecuteSqlHandler(datasources, new QueryExecutor(datasources), new ReadOnlyGuard(),
                new StatementParser(datasources));
        datasources.ensureConnected("h2db");
        try (Connection connection = datasources.poolFor("h2db").getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE items (id INT PRIMARY KEY)");
            statement.execute("INSERT INTO items (id) VALUES (1), (2), (3), (4), (5)");
        }
    }

    @AfterEach
    void tearDown() {
        datasources.close();
    }

    @Test
    void executesSelectInReadonlyMode() {
        Map<String, Object> data = handler.execute("h2db", "SELECT 1", true, 100);

        assertEquals(1, statements(data).size());
        assertEquals("h2db", data.get("source_id"));
    }

    @Test
    void deniesWriteStatementsInReadonlyMode() {
        ToolError error = assertThrows(ToolError.class, () -> handler.execute("h2db", "DELETE FROM items", true, 100));

        assertEquals(ToolEnvelope.CODE_READONLY_VIOLATION, error.code());
    }

    @Test
    void emptySqlIsDeniedInReadonlyMode() {
        ToolError error = assertThrows(ToolError.class, () -> handler.execute("h2db", "", true, 100));

        assertEquals(ToolEnvelope.CODE_READONLY_VIOLATION, error.code());
    }

    @Test
    void writableSourceExecutesWrites() {
        Map<String, Object> data = handler.execute("h2db", "DELETE FROM items WHERE id > 3", false, 100);

        assertEquals(2, statements(data).get(0).get("rowCount"));
    }

    @Test
    void truncatesBeyondMaxRows() {
        Map<String, Object> data = handler.execute("h2db", "SELECT id FROM items ORDER BY id", true, 3);

        Map<String, Object> statement = statements(data).get(0);
        assertEquals(3, ((List<?>) statement.get("rows")).size());
        assertEquals(Boolean.TRUE, statement.get("truncated"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> statements(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("statements");
    }
}
