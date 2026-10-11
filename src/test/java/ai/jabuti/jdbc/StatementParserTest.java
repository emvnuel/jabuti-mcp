package ai.jabuti.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.testsupport.H2Sources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The statement pipeline's interface: splitting a tool call's SQL and
 * classifying each statement (the privilege matrix moved here from the
 * read-only gate, which now only consumes batches).
 */
class StatementParserTest {

    private DatasourceManager datasources;
    private StatementParser parser;

    @BeforeEach
    void setUp() {
        datasources = H2Sources.uniqueManager();
        parser = new StatementParser(datasources);
    }

    @AfterEach
    void tearDown() {
        datasources.close();
    }

    private Statement single(String sql) {
        StatementBatch batch = parser.parse("h2db", sql);
        assertEquals(1, batch.statements().size(), batch.statements().toString());
        return batch.statements().get(0);
    }

    @Test
    void classifiesReadStatements() {
        assertEquals(StatementClass.READ, single("SELECT 1").statementClass());
        assertEquals(StatementClass.READ, single("WITH x AS (SELECT 1) SELECT * FROM x").statementClass());
        assertEquals(StatementClass.READ, single("  select * from t where a = ';' ").statementClass());
    }

    @Test
    void classifiesDml() {
        assertEquals(StatementClass.DML, single("INSERT INTO t VALUES (1)").statementClass());
        assertEquals(StatementClass.DML, single("UPDATE t SET a = 1").statementClass());
        assertEquals(StatementClass.DML, single("DELETE FROM t").statementClass());
        assertEquals(StatementClass.DML, single("MERGE INTO t USING s ON 1 = 1 WHEN MATCHED THEN DELETE").statementClass());
        assertEquals(StatementClass.DML, single("REPLACE INTO t VALUES (1)").statementClass());
    }

    @Test
    void classifiesDdl() {
        assertEquals(StatementClass.DDL, single("CREATE TABLE t (a INT)").statementClass());
        assertEquals(StatementClass.DDL, single("CREATE INDEX i ON t (a)").statementClass());
        assertEquals(StatementClass.DDL, single("ALTER TABLE t ADD COLUMN b INT").statementClass());
        assertEquals(StatementClass.DDL, single("DROP TABLE t").statementClass());
        assertEquals(StatementClass.DDL, single("TRUNCATE TABLE t").statementClass());
    }

    @Test
    void classifiesAdmin() {
        assertEquals(StatementClass.ADMIN, single("GRANT SELECT ON t TO u").statementClass());
        assertEquals(StatementClass.ADMIN, single("REVOKE SELECT ON t FROM u").statementClass());
        assertEquals(StatementClass.ADMIN, single("EXECUTE p").statementClass());
    }

    @Test
    void commentPrefixedDeleteIsStillDml() {
        assertEquals(StatementClass.DML, single("/*x*/ DELETE FROM t").statementClass());
        assertEquals(StatementClass.DML, single("-- note\nDELETE FROM t").statementClass());
    }

    @Test
    void unknownForExplainShowSetAndParseFailures() {
        assertEquals(StatementClass.UNKNOWN, single("EXPLAIN SELECT 1").statementClass());
        assertEquals(StatementClass.UNKNOWN, single("SHOW TABLES").statementClass());
        assertEquals(StatementClass.UNKNOWN, single("SET search_path = public").statementClass());
        assertEquals(StatementClass.UNKNOWN, single("VACUUM").statementClass());
        assertEquals(StatementClass.UNKNOWN, single("NOT SQL AT ALL").statementClass());
    }

    @Test
    void dataModifyingCteBodiesAreNotRead() {
        assertEquals(StatementClass.DML,
                single("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x").statementClass());
        assertEquals(StatementClass.DML,
                single("WITH x AS (INSERT INTO t VALUES (1) RETURNING *) SELECT * FROM x").statementClass());
        assertEquals(StatementClass.DML,
                single("WITH x AS (UPDATE t SET a = 1 RETURNING *) SELECT * FROM x").statementClass());
        assertEquals(StatementClass.READ, single("WITH x AS (SELECT 1) SELECT * FROM x").statementClass());
    }

    @Test
    void parenthesizedSelectDoesNotHideDataModifyingCte() {
        assertEquals(StatementClass.DML,
                single("(WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x)").statementClass());
        assertEquals(StatementClass.DML,
                single("((WITH x AS (INSERT INTO t VALUES (1) RETURNING *) SELECT * FROM x))").statementClass());
        assertEquals(StatementClass.DML,
                single("(WITH x AS (DELETE FROM t RETURNING *) SELECT 1 UNION SELECT 2)").statementClass());
        assertEquals(StatementClass.READ, single("(SELECT 1)").statementClass());
        assertEquals(StatementClass.READ, single("(WITH x AS (SELECT 1) SELECT * FROM x)").statementClass());
    }

    @Test
    void splitsIntoSeparateStatements() {
        List<Statement> statements = parser.parse("h2db", "SELECT 1; SELECT 2;").statements();

        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(0).text());
        assertEquals("SELECT 2", statements.get(1).text());
    }

    @Test
    void blankInputYieldsAnEmptyBatch() {
        assertTrue(parser.parse("h2db", "").isEmpty());
        assertTrue(parser.parse("h2db", "   ").isEmpty());
        assertTrue(parser.parse("h2db", " ; ; ").isEmpty());
        assertTrue(parser.parse("h2db", null).isEmpty());
    }

    @Test
    void mysqlLexerRulesSplitBackslashEscapedStringsDifferently() {
        String sql = "SELECT 'a\\' -- ' ; DELETE FROM t";

        List<Statement> standard = parser.parse("h2db", sql).statements();
        assertEquals(1, standard.size());
        assertEquals(StatementClass.READ, standard.get(0).statementClass());

        DatasourceManager mysql = H2Sources.manager("jdbc:mysql://localhost/x");
        try {
            List<Statement> escaped = new StatementParser(mysql).parse("h2db", sql).statements();
            assertEquals(2, escaped.size());
            assertEquals(StatementClass.DML, escaped.get(1).statementClass());
        } finally {
            mysql.close();
        }
    }

    @Test
    void returnsRowsTracksRowProducingStatements() {
        assertTrue(single("SELECT 1").returnsRows());
        assertTrue(single("WITH x AS (SELECT 1) SELECT * FROM x").returnsRows());
        assertTrue(single("EXPLAIN SELECT 1").returnsRows());
        assertTrue(single("SHOW TABLES").returnsRows());
        assertFalse(single("UPDATE t SET a = 1").returnsRows());
        assertFalse(single("INSERT INTO t VALUES (1)").returnsRows());
        assertFalse(single("DELETE FROM t").returnsRows());
        assertFalse(single("CREATE TABLE t (a INT)").returnsRows());
    }

    @Test
    void firstKeywordAndStrippedTextIgnoreCommentsAndStrings() {
        Statement statement = single("/*x*/ SELECT 'DELETE'");

        assertEquals("SELECT", statement.firstKeyword());
        assertEquals("SELECT", statement.strippedText());
    }

    @Test
    void classificationIsMemoized() {
        Statement statement = single("DELETE FROM t");

        assertEquals(StatementClass.DML, statement.statementClass());
        assertEquals(StatementClass.DML, statement.statementClass());
    }
}
