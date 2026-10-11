package ai.jabuti.dialect;

import java.util.regex.Pattern;

import ai.jabuti.jdbc.Statement;
import ai.jabuti.jdbc.StatementBatch;

/**
 * Validates that a parsed batch is explainable and rejects input that could
 * turn the tool's own EXPLAIN prefix into something that executes the
 * statement (ported from dbhub's {@code explain-sql.ts}). Deliberately never
 * emits ANALYZE.
 */
public final class ExplainBuilder {

    private static final Pattern ANALYZE = Pattern.compile("\\banaly[sz]e\\b", Pattern.CASE_INSENSITIVE);
    private static final String ANALYZE_REJECTION =
            "explain_sql does not support ANALYZE (it must never execute the statement)";

    private ExplainBuilder() {
    }

    /**
     * Returns an error message when the batch is not explainable, or
     * {@code null} when it is.
     */
    public static String validate(StatementBatch batch) {
        if (batch.isEmpty()) {
            return "explain_sql requires a non-empty SQL statement";
        }
        if (batch.statements().size() != 1) {
            return "explain_sql only supports a single SQL statement";
        }
        Statement statement = batch.statements().get(0);
        String firstWord = statement.firstKeyword();
        if ("explain".equalsIgnoreCase(firstWord)) {
            return "explain_sql input must not itself start with EXPLAIN";
        }
        if (ANALYZE.matcher(firstWord).matches()) {
            return ANALYZE_REJECTION;
        }
        // PostgreSQL accepts a parenthesized option list right after EXPLAIN
        // (e.g. EXPLAIN (ANALYZE, VERBOSE) SELECT ...); such input would combine
        // with our EXPLAIN prefix into a form that executes the statement.
        String cleaned = statement.strippedText();
        if (cleaned.startsWith("(")) {
            int close = cleaned.indexOf(')');
            if (close > 0) {
                String options = cleaned.substring(0, close + 1);
                if (ANALYZE.matcher(options).find()) {
                    return ANALYZE_REJECTION;
                }
            }
        }
        return null;
    }
}
