package ai.jabuti.dialect;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.jdbc.StatementBatch;
import ai.jabuti.jdbc.StatementParser;
import ai.jabuti.testsupport.H2Sources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Explainability validation over parsed batches, through the same interface
 * the handler uses.
 */
class ExplainBuilderTest {

    private DatasourceManager standard;
    private DatasourceManager mysql;
    private StatementParser parser;
    private StatementParser mysqlParser;

    @BeforeEach
    void setUp() {
        standard = H2Sources.uniqueManager();
        mysql = H2Sources.manager("jdbc:mysql://localhost/x");
        parser = new StatementParser(standard);
        mysqlParser = new StatementParser(mysql);
    }

    @AfterEach
    void tearDown() {
        standard.close();
        mysql.close();
    }

    private StatementBatch batch(String sql) {
        return parser.parse("h2db", sql);
    }

    @Test
    void acceptsSingleReadStatement() {
        assertNull(ExplainBuilder.validate(batch("SELECT * FROM t")));
        assertNull(ExplainBuilder.validate(batch("/* hint */ SELECT 1")));
    }

    @Test
    void rejectsMultipleStatements() {
        assertNotNull(ExplainBuilder.validate(batch("SELECT 1; SELECT 2")));
    }

    @Test
    void rejectsExplainAndAnalyzeInput() {
        assertNotNull(ExplainBuilder.validate(batch("EXPLAIN SELECT 1")));
        assertNotNull(ExplainBuilder.validate(batch("-- noise\nEXPLAIN SELECT 1")));
        assertNotNull(ExplainBuilder.validate(batch("ANALYZE SELECT 1")));
        assertNotNull(ExplainBuilder.validate(batch("(ANALYZE, VERBOSE) SELECT 1")));
        assertNotNull(ExplainBuilder.validate(batch("(analyze) SELECT 1")));
    }

    @Test
    void rejectsBritishAnalyseSpelling() {
        assertNotNull(ExplainBuilder.validate(batch("analyse SELECT 1")));
        assertNotNull(ExplainBuilder.validate(batch("ANALYSE SELECT 1")));
        assertNotNull(ExplainBuilder.validate(batch("(ANALYSE) SELECT 1")));
        assertNotNull(ExplainBuilder.validate(batch("(ANALYSE, VERBOSE) SELECT 1")));
        assertNotNull(ExplainBuilder.validate(batch("(analyse, buffers) DELETE FROM t")));
    }

    @Test
    void mysqlBackslashEscapesRevealMultipleStatements() {
        String sql = "SELECT 'a\\' -- ' ; DELETE FROM t";

        assertNull(ExplainBuilder.validate(batch(sql)));
        assertNotNull(ExplainBuilder.validate(mysqlParser.parse("h2db", sql)));
    }

    @Test
    void acceptsParenOptionsWithoutAnalyze() {
        assertNull(ExplainBuilder.validate(batch("(VERBOSE) SELECT 1")));
    }

    @Test
    void rejectsEmptyInput() {
        assertNotNull(ExplainBuilder.validate(batch("")));
        assertNotNull(ExplainBuilder.validate(batch("   ")));
        assertNotNull(ExplainBuilder.validate(batch(null)));
    }
}
