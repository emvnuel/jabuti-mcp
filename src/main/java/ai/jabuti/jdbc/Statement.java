package ai.jabuti.jdbc;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.ParenthesedStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.grant.Grant;
import net.sf.jsqlparser.statement.grant.Revoke;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;

/**
 * One statement of a source's SQL: its text, its lexer-level facts (stripped
 * text, first keyword) and its lazily classified {@link StatementClass}.
 * Classification parses with JSqlParser on first ask and caches the verdict;
 * a parse failure is {@code UNKNOWN}. Instances are not thread-safe — one
 * batch belongs to one tool call.
 */
public final class Statement {

    private static final Set<String> ROW_RETURNING_KEYWORDS = Set.of(
            "SELECT", "WITH", "EXPLAIN", "SHOW", "DESCRIBE", "DESC", "VALUES", "PRAGMA", "TABLE");

    private final String text;
    private final String strippedText;
    private StatementClass cachedClass;

    private Statement(String text, String strippedText) {
        this.text = text;
        this.strippedText = strippedText;
    }

    static Statement of(String text, boolean backslashEscapes) {
        return new Statement(text, SqlSplitter.stripCommentsAndStrings(text, backslashEscapes).trim());
    }

    /**
     * A statement the server generated itself (row counts, EXPLAIN); it never
     * goes through JSqlParser because the server wrote it.
     */
    static Statement trusted(String text) {
        return of(text, false);
    }

    public String text() {
        return text;
    }

    /**
     * The statement with comments and string/identifier literals blanked, so
     * keyword inspection never sees text hidden inside them.
     */
    public String strippedText() {
        return strippedText;
    }

    /**
     * The first keyword of {@link #strippedText()}.
     */
    public String firstKeyword() {
        return SqlSplitter.firstWord(strippedText);
    }

    /**
     * Whether the statement is expected to return a result set; drives the
     * executor's row cap ({@code UPDATE}-style statements return an update
     * count instead).
     */
    boolean returnsRows() {
        return ROW_RETURNING_KEYWORDS.contains(firstKeyword().toUpperCase(Locale.ROOT));
    }

    /**
     * The statement's privilege class, parsed on first call and cached. A
     * statement JSqlParser cannot parse (or one that is not a recognizable
     * data statement) is {@code UNKNOWN} — the most privileged verdict.
     */
    public StatementClass statementClass() {
        StatementClass result = cachedClass;
        if (result == null) {
            result = classify(text);
            cachedClass = result;
        }
        return result;
    }

    private static StatementClass classify(String sql) {
        try {
            return classifyStatement(CCJSqlParserUtil.parse(sql));
        } catch (JSQLParserException | RuntimeException e) {
            return StatementClass.UNKNOWN;
        }
    }

    private static StatementClass classifyStatement(net.sf.jsqlparser.statement.Statement statement) {
        if (statement instanceof ParenthesedSelect parenthesed) {
            // A parenthesized select keeps its WITH list on the inner select.
            Select inner = parenthesed.getSelect();
            return inner == null ? StatementClass.UNKNOWN : classifyStatement(inner);
        }
        if (statement instanceof Select select) {
            return mostPrivileged(StatementClass.READ, classifyWithItems(select));
        }
        if (statement instanceof Insert || statement instanceof Update || statement instanceof Delete
                || statement instanceof Merge || statement instanceof Upsert) {
            return StatementClass.DML;
        }
        if (statement instanceof Alter || statement instanceof Drop || statement instanceof Truncate
                || statement.getClass().getName().startsWith("net.sf.jsqlparser.statement.create.")) {
            return StatementClass.DDL;
        }
        if (statement instanceof Grant || statement instanceof Revoke || statement instanceof Execute) {
            return StatementClass.ADMIN;
        }
        return StatementClass.UNKNOWN;
    }

    /**
     * PostgreSQL allows data-modifying statements inside the {@code WITH}
     * clause of a top-level {@code SELECT} ({@code WITH x AS (DELETE ...)
     * SELECT ...}); JSqlParser parses those bodies as {@code ParenthesedDelete}
     * / {@code ParenthesedInsert} / {@code ParenthesedUpdate} wrappers, so each
     * one is classified like a top-level statement.
     */
    private static StatementClass classifyWithItems(Select select) {
        StatementClass result = StatementClass.READ;
        List<WithItem<?>> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem<?> item : withItems) {
                ParenthesedStatement body = item.getStatement();
                StatementClass bodyClass = body == null ? StatementClass.UNKNOWN : classifyStatement(body);
                result = mostPrivileged(result, bodyClass);
            }
        }
        return result;
    }

    private static StatementClass mostPrivileged(StatementClass a, StatementClass b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
