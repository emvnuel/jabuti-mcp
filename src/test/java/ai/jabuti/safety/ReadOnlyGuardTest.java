package ai.jabuti.safety;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.jdbc.StatementBatch;
import ai.jabuti.jdbc.StatementParser;
import ai.jabuti.testsupport.H2Sources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read-only policy over parsed batches. Classification itself is the
 * parser's behaviour and is covered by {@code StatementParserTest}.
 */
class ReadOnlyGuardTest {

    private final ReadOnlyGuard guard = new ReadOnlyGuard();
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

    private StatementBatch batch(String sql) {
        return parser.parse("h2db", sql);
    }

    @Test
    void readonlyAllowsOnlyRead() {
        assertTrue(guard.isAllowed(true, batch("SELECT 1")));
        assertFalse(guard.isAllowed(true, batch("DELETE FROM t")));
        assertFalse(guard.isAllowed(true, batch("DROP TABLE t")));
        assertFalse(guard.isAllowed(true, batch("GRANT SELECT ON t TO u")));
        assertFalse(guard.isAllowed(true, batch("EXPLAIN SELECT 1")));
        assertFalse(guard.isAllowed(true, batch("NOT SQL AT ALL")));
    }

    @Test
    void emptyBatchIsDeniedInReadonlyMode() {
        assertFalse(guard.isAllowed(true, batch("")));
        assertFalse(guard.isAllowed(true, batch("   ")));
    }

    @Test
    void writeModeAllowsEverything() {
        assertTrue(guard.isAllowed(false, batch("SELECT 1")));
        assertTrue(guard.isAllowed(false, batch("DELETE FROM t")));
        assertTrue(guard.isAllowed(false, batch("NOT SQL AT ALL")));
        assertTrue(guard.isAllowed(false, batch("")));
    }

    @Test
    void multiStatementBatchTakesTheStrictestClass() {
        assertFalse(guard.isAllowed(true, batch("SELECT 1; DELETE FROM t")));
        assertTrue(guard.isAllowed(true, batch("SELECT 1; SELECT 2")));
        assertFalse(guard.isAllowed(true, batch("SELECT 1; VACUUM")));
    }

    @Test
    void dataModifyingCteBodiesAreNotRead() {
        assertFalse(guard.isAllowed(true, batch("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x")));
        assertFalse(guard.isAllowed(true, batch("(WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x)")));
    }

    @Test
    void mysqlBackslashEscapesAreHonoredForTheSource() {
        String sql = "SELECT 'a\\' -- ' ; DELETE FROM t";

        assertTrue(guard.isAllowed(true, batch(sql)));

        DatasourceManager mysql = H2Sources.manager("jdbc:mysql://localhost/x");
        try {
            assertFalse(guard.isAllowed(true, new StatementParser(mysql).parse("h2db", sql)));
        } finally {
            mysql.close();
        }
    }
}
