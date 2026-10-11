package ai.jabuti.jdbc;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits SQL into individual statements, respecting single quotes, double
 * quotes, backticks, bracket identifiers and {@code --} / {@code /* *}{@code /}
 * comments (a port of dbhub's {@code splitSQLStatements}). Internal to the
 * {@link StatementParser} pipeline; the parser's interface is the one
 * callers (and tests outside this package) go through.
 *
 * <p>Known MVP limitation: PostgreSQL dollar-quoted bodies ({@code $$...$$})
 * are not treated as quoted blocks, so a function body containing {@code ;}
 * may split incorrectly.
 */
final class SqlSplitter {

    private record Token(int end, boolean plain) {
    }

    private SqlSplitter() {
    }

    public static List<String> split(String sql) {
        return split(sql, false);
    }

    /**
     * Splits SQL using the lexer rules of the target database.
     *
     * @param backslashEscapes {@code true} to honor backslash escapes inside
     *     single- and double-quoted strings (MySQL/MariaDB semantics); {@code
     *     false} for standard SQL, where only doubled quotes escape
     */
    public static List<String> split(String sql, boolean backslashEscapes) {
        List<String> statements = new ArrayList<>();
        if (sql == null) {
            return statements;
        }
        int statementStart = 0;
        int i = 0;
        while (i < sql.length()) {
            if (sql.charAt(i) == ';') {
                addIfNotBlank(statements, sql.substring(statementStart, i));
                statementStart = i + 1;
                i++;
                continue;
            }
            Token token = scan(sql, i, backslashEscapes);
            i = token.end();
        }
        addIfNotBlank(statements, sql.substring(statementStart));
        return statements;
    }

    /**
     * Replaces comments and quoted blocks with a single space each, so keyword
     * detection never sees text hidden inside literals or comments.
     */
    public static String stripCommentsAndStrings(String sql) {
        return stripCommentsAndStrings(sql, false);
    }

    /**
     * Like {@link #stripCommentsAndStrings(String)} with the target database's
     * lexer rules for backslash escapes.
     */
    public static String stripCommentsAndStrings(String sql, boolean backslashEscapes) {
        if (sql == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            Token token = scan(sql, i, backslashEscapes);
            if (token.plain()) {
                result.append(sql.charAt(i));
            } else {
                result.append(' ');
            }
            i = token.end();
        }
        return result.toString();
    }

    /**
     * The first whitespace-delimited word of a statement, for keyword
     * detection on text already passed through
     * {@link #stripCommentsAndStrings(String)}.
     */
    public static String firstWord(String sql) {
        int end = 0;
        while (end < sql.length() && !Character.isWhitespace(sql.charAt(end))) {
            end++;
        }
        return sql.substring(0, end);
    }

    private static void addIfNotBlank(List<String> statements, String candidate) {
        String trimmed = candidate.trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
    }

    private static Token scan(String sql, int i, boolean backslashEscapes) {
        char c = sql.charAt(i);
        if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
            int end = i;
            while (end < sql.length() && sql.charAt(end) != '\n') {
                end++;
            }
            return new Token(end, false);
        }
        if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
            int end = i + 2;
            while (end < sql.length() && !(sql.charAt(end) == '*' && end + 1 < sql.length() && sql.charAt(end + 1) == '/')) {
                end++;
            }
            return new Token(end < sql.length() ? end + 2 : end, false);
        }
        if (c == '\'' || c == '"' || c == '`') {
            return quoted(sql, i, c, backslashEscapes);
        }
        if (c == '[') {
            return quoted(sql, i, ']', backslashEscapes);
        }
        return new Token(i + 1, true);
    }

    private static Token quoted(String sql, int i, char close, boolean backslashEscapes) {
        boolean backslashable = backslashEscapes && (close == '\'' || close == '"');
        int end = i + 1;
        while (end < sql.length()) {
            if (backslashable && sql.charAt(end) == '\\' && end + 1 < sql.length()) {
                end += 2;
            } else if (sql.charAt(end) == close && end + 1 < sql.length() && sql.charAt(end + 1) == close) {
                end += 2;
            } else if (sql.charAt(end) == close) {
                end++;
                break;
            } else {
                end++;
            }
        }
        return new Token(end, false);
    }
}
