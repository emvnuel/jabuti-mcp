package ai.jabuti.jdbc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows and affected-row count produced by one statement in a batch.
 * {@code truncated} is only ever {@code TRUE} (never {@code false}) so a capped
 * result is distinguishable from a table that genuinely has exactly
 * {@code maxRows} rows.
 */
public record ResultSetPayload(String sql, List<Map<String, Object>> rows, int rowCount, Boolean truncated) {

    /**
     * A payload for a statement that produced no rows.
     */
    public static ResultSetPayload empty(String sql) {
        return new ResultSetPayload(sql, List.of(), 0, null);
    }

    /**
     * The statement's wire shape ({@code sql/rows/rowCount}, with
     * {@code truncated} omitted unless set).
     */
    public Map<String, Object> toStatementPayload() {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("sql", sql);
        statement.put("rows", rows);
        statement.put("rowCount", rowCount);
        if (truncated != null) {
            statement.put("truncated", truncated);
        }
        return statement;
    }
}
