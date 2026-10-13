package ai.jabuti.jdbc;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import ai.jabuti.datasource.DatasourceManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Executes a {@link StatementBatch} against a source's pool, one
 * {@link ResultSetPayload} per statement. Statements that return rows are
 * capped at {@code maxRows} using a {@code maxRows + 1} probe so truncation
 * is exact; other statements return their update count. Read-only
 * enforcement is not done here: {@code execute_sql} gates through
 * {@code ReadOnlyGuard} before calling this executor.
 */
@Singleton
public class QueryExecutor {

    public static final int QUERY_TIMEOUT_SECONDS = 30;

    private static final long MAX_SAFE_LONG = 9_007_199_254_740_991L;
    private static final BigInteger MIN_SAFE = BigInteger.valueOf(-MAX_SAFE_LONG);
    private static final BigInteger MAX_SAFE = BigInteger.valueOf(MAX_SAFE_LONG);

    private final DatasourceManager datasourceManager;

    @Inject
    public QueryExecutor(DatasourceManager datasourceManager) {
        this.datasourceManager = datasourceManager;
    }

    public List<ResultSetPayload> execute(StatementBatch batch, int maxRows) {
        return datasourceManager.withConnection(batch.sourceId(), connection -> {
            List<ResultSetPayload> payloads = new ArrayList<>();
            for (Statement statement : batch.statements()) {
                payloads.add(executeStatement(connection, statement, maxRows));
            }
            return payloads;
        });
    }

    /**
     * The source's row count for a table, quoted for its dialect. Empty when
     * the count came back without a usable value.
     */
    public OptionalInt countRows(String sourceId, String schema, String table) {
        String qualified = datasourceManager.dialect(sourceId).qualify(schema, table);
        List<ResultSetPayload> payloads = execute(
                StatementBatch.trusted(sourceId, "SELECT COUNT(*) AS count FROM " + qualified), 1);
        if (payloads.isEmpty() || payloads.get(0).rows().isEmpty()) {
            return OptionalInt.empty();
        }
        Object value = payloads.get(0).rows().get(0).values().iterator().next();
        if (value instanceof Number number) {
            return OptionalInt.of(number.intValue());
        }
        if (value == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(value.toString()));
    }

    private ResultSetPayload executeStatement(Connection connection, Statement statement, int maxRows)
            throws SQLException {
        try (java.sql.Statement jdbcStatement = connection.createStatement()) {
            jdbcStatement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            if (statement.returnsRows()) {
                jdbcStatement.setMaxRows(maxRows + 1);
            }
            if (jdbcStatement.execute(statement.text())) {
                try (ResultSet resultSet = jdbcStatement.getResultSet()) {
                    return truncate(statement.text(), readRows(resultSet, maxRows), maxRows);
                }
            }
            return new ResultSetPayload(statement.text(), List.of(), jdbcStatement.getUpdateCount(), null);
        }
    }

    /**
     * Caps the probe rows at {@code maxRows}; one extra row means the result
     * was truncated.
     */
    private static ResultSetPayload truncate(String sql, List<Map<String, Object>> rows, int maxRows) {
        if (rows.size() <= maxRows) {
            return new ResultSetPayload(sql, rows, rows.size(), null);
        }
        List<Map<String, Object>> capped = new ArrayList<>(rows.subList(0, maxRows));
        return new ResultSetPayload(sql, capped, capped.size(), Boolean.TRUE);
    }

    private static List<Map<String, Object>> readRows(ResultSet resultSet, int maxRows) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metaData = resultSet.getMetaData();
        while (resultSet.next() && rows.size() <= maxRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= metaData.getColumnCount(); column++) {
                row.put(metaData.getColumnLabel(column), normalize(resultSet.getObject(column)));
            }
            rows.add(row);
        }
        return rows;
    }

    private static Object normalize(Object value) {
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Long longValue && (longValue > MAX_SAFE_LONG || longValue < -MAX_SAFE_LONG)) {
            return longValue.toString();
        }
        if (value instanceof BigInteger bigInteger) {
            if (bigInteger.compareTo(MIN_SAFE) < 0 || bigInteger.compareTo(MAX_SAFE) > 0) {
                return bigInteger.toString();
            }
            return bigInteger.longValue();
        }
        return value;
    }
}
