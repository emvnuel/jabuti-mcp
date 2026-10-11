package ai.jabuti.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.testsupport.H2Sources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryExecutorTest {

    private DatasourceManager datasources;
    private QueryExecutor executor;
    private StatementParser parser;

    @BeforeEach
    void setUp() throws Exception {
        datasources = H2Sources.uniqueManager();
        executor = new QueryExecutor(datasources);
        parser = new StatementParser(datasources);
        datasources.ensureConnected("h2db");
        try (Connection connection = datasources.poolFor("h2db").getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (a INT)");
            statement.execute("INSERT INTO t VALUES (1), (2), (3), (4), (5)");
        }
    }

    @AfterEach
    void tearDown() {
        datasources.close();
    }

    private List<ResultSetPayload> execute(String sql, int maxRows) {
        return executor.execute(parser.parse("h2db", sql), maxRows);
    }

    @Test
    void executesSingleSelect() {
        List<ResultSetPayload> payloads = execute("SELECT 1", 100);

        assertEquals(1, payloads.size());
        ResultSetPayload payload = payloads.get(0);
        assertEquals("SELECT 1", payload.sql());
        assertEquals(1, payload.rows().size());
        assertEquals(1, payload.rows().get(0).values().iterator().next());
        assertNull(payload.truncated());
    }

    @Test
    void executesMultipleStatementsWithOnePayloadEach() {
        List<ResultSetPayload> payloads = execute("SELECT 1; SELECT 2", 100);

        assertEquals(2, payloads.size());
        assertEquals(1, payloads.get(0).rows().get(0).values().iterator().next());
        assertEquals(2, payloads.get(1).rows().get(0).values().iterator().next());
    }

    @Test
    void truncatesAtMaxRowsWithProbe() {
        List<ResultSetPayload> payloads = execute("SELECT a FROM t ORDER BY a", 3);

        ResultSetPayload payload = payloads.get(0);
        assertEquals(3, payload.rows().size());
        assertEquals(3, payload.rowCount());
        assertEquals(Boolean.TRUE, payload.truncated());
    }

    @Test
    void doesNotFlagTruncationWhenRowsFit() {
        List<ResultSetPayload> payloads = execute("SELECT a FROM t ORDER BY a", 10);

        ResultSetPayload payload = payloads.get(0);
        assertEquals(5, payload.rows().size());
        assertEquals(5, payload.rowCount());
        assertNull(payload.truncated());
    }

    @Test
    void exactlyMaxRowsIsNotTruncated() {
        List<ResultSetPayload> payloads = execute("SELECT a FROM t ORDER BY a", 5);

        assertNull(payloads.get(0).truncated());
        assertEquals(5, payloads.get(0).rows().size());
    }

    @Test
    void updateReturnsRowCountAndNoRows() {
        List<ResultSetPayload> payloads = execute("UPDATE t SET a = a + 0", 100);

        ResultSetPayload payload = payloads.get(0);
        assertEquals(5, payload.rowCount());
        assertTrue(payload.rows().isEmpty());
        assertNull(payload.truncated());
    }

    @Test
    void binaryColumnsAreBase64Encoded() {
        List<ResultSetPayload> payloads = execute("SELECT X'0102' AS payload", 100);

        Object value = payloads.get(0).rows().get(0).values().iterator().next();
        assertEquals("AQI=", value);
    }

    @Test
    void integersBeyondSafeLongBecomeStrings() {
        List<ResultSetPayload> payloads = execute("SELECT CAST(9007199254740992 AS BIGINT) AS big", 100);

        Object value = payloads.get(0).rows().get(0).values().iterator().next();
        assertEquals("9007199254740992", value);
    }

    @Test
    void rowsAreKeyedByColumnLabel() {
        List<ResultSetPayload> payloads = execute("SELECT a AS \"total\" FROM t ORDER BY a", 100);

        Map<String, Object> row = payloads.get(0).rows().get(0);
        assertEquals(1, row.get("total"));
    }
}
