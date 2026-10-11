package ai.jabuti.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.jdbc.QueryExecutor;
import ai.jabuti.jdbc.ResultSetPayload;
import ai.jabuti.jdbc.StatementBatch;
import ai.jabuti.jdbc.StatementParser;
import ai.jabuti.safety.ReadOnlyGuard;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The {@code execute_sql} tool: gates the SQL through {@link ReadOnlyGuard}
 * (readonly sources deny anything but {@code READ}) and executes every
 * statement in the batch, returning one payload per statement (mirrors
 * dbhub's {@code createExecuteSqlToolHandler}). Returns envelope data or
 * throws {@link ToolError}; {@link ToolCalls} owns the wire shape.
 */
@Singleton
public class ExecuteSqlHandler {

    private final DatasourceManager datasourceManager;
    private final QueryExecutor queryExecutor;
    private final ReadOnlyGuard readOnlyGuard;
    private final StatementParser statementParser;

    @Inject
    public ExecuteSqlHandler(DatasourceManager datasourceManager, QueryExecutor queryExecutor,
            ReadOnlyGuard readOnlyGuard, StatementParser statementParser) {
        this.datasourceManager = datasourceManager;
        this.queryExecutor = queryExecutor;
        this.readOnlyGuard = readOnlyGuard;
        this.statementParser = statementParser;
    }

    public Map<String, Object> execute(String sourceId, String sql, boolean readonly, int maxRows) {
        datasourceManager.ensureConnected(sourceId);

        StatementBatch batch = statementParser.parse(sourceId, sql);
        if (!readOnlyGuard.isAllowed(readonly, batch)) {
            throw new ToolError(
                    "Read-only mode is enabled. Only read-only statements (SELECT) are allowed.",
                    ToolEnvelope.CODE_READONLY_VIOLATION);
        }

        List<ResultSetPayload> payloads = queryExecutor.execute(batch, maxRows);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("statements", payloads.stream().map(ResultSetPayload::toStatementPayload).toList());
        data.put("source_id", sourceId);
        return data;
    }
}
