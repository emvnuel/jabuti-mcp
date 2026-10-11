package ai.jabuti.jdbc;

import java.util.List;

/**
 * The statements parsed from one tool call, in execution order. Carries the
 * source they belong to, so a batch can be executed without re-threading the
 * source id.
 */
public record StatementBatch(String sourceId, List<Statement> statements) {

    public StatementBatch {
        statements = List.copyOf(statements);
    }

    public boolean isEmpty() {
        return statements.isEmpty();
    }

    /**
     * A one-statement batch for SQL the server generated itself (row counts,
     * EXPLAIN). Trusted: it never goes through JSqlParser.
     */
    public static StatementBatch trusted(String sourceId, String sql) {
        return new StatementBatch(sourceId, List.of(Statement.trusted(sql)));
    }
}
