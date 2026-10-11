package ai.jabuti.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.dialect.DbFamily;
import ai.jabuti.dialect.ExplainBuilder;
import ai.jabuti.jdbc.QueryExecutor;
import ai.jabuti.jdbc.ResultSetPayload;
import ai.jabuti.jdbc.StatementBatch;
import ai.jabuti.jdbc.StatementParser;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The opt-in {@code explain_sql} tool: validates the parsed batch, builds
 * the dialect-appropriate EXPLAIN and always executes it read-only — the
 * target statement is never executed, regardless of the source's own
 * readonly/write configuration (mirrors dbhub's {@code explain-sql.ts}).
 * Returns envelope data or throws {@link ToolError}.
 */
@Singleton
public class ExplainSqlHandler {

    private static final int EXPLAIN_MAX_ROWS = 1000;

    private final DatasourceManager datasourceManager;
    private final QueryExecutor queryExecutor;
    private final StatementParser statementParser;

    @Inject
    public ExplainSqlHandler(DatasourceManager datasourceManager, QueryExecutor queryExecutor,
            StatementParser statementParser) {
        this.datasourceManager = datasourceManager;
        this.queryExecutor = queryExecutor;
        this.statementParser = statementParser;
    }

    public Map<String, Object> explain(String sourceId, String sql) {
        DbFamily family = datasourceManager.family(sourceId);
        if (!family.supportsExplain()) {
            throw new ToolError("explain_sql is not supported for source '" + sourceId + "' yet",
                    ToolEnvelope.CODE_UNSUPPORTED);
        }

        StatementBatch batch = statementParser.parse(sourceId, sql);
        String validationError = ExplainBuilder.validate(batch);
        if (validationError != null) {
            throw new ToolError(validationError, ToolEnvelope.CODE_INVALID_INPUT);
        }

        String statement = family.explain(batch.statements().get(0).text());
        ResultSetPayload payload = firstPayload(
                queryExecutor.execute(StatementBatch.trusted(sourceId, statement), EXPLAIN_MAX_ROWS), statement);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", payload.rows());
        data.put("count", payload.rowCount());
        data.put("source_id", sourceId);
        return data;
    }

    private static ResultSetPayload firstPayload(List<ResultSetPayload> payloads, String statement) {
        return payloads.isEmpty() ? ResultSetPayload.empty(statement) : payloads.get(0);
    }
}
