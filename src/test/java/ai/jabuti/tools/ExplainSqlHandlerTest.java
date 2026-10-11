package ai.jabuti.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.jdbc.QueryExecutor;
import ai.jabuti.jdbc.StatementParser;
import ai.jabuti.testsupport.H2Sources;
import org.junit.jupiter.api.Test;

/**
 * The failure paths of {@code explain_sql} that do not need a database:
 * unsupported families and validation rejections that happen before any
 * connection is opened.
 */
class ExplainSqlHandlerTest {

    @Test
    void unsupportedFamilyIsRejected() {
        DatasourceManager datasources = H2Sources.uniqueManager();
        try {
            ExplainSqlHandler handler = new ExplainSqlHandler(datasources, new QueryExecutor(datasources),
                    new StatementParser(datasources));

            ToolError error = assertThrows(ToolError.class, () -> handler.explain("h2db", "SELECT 1"));

            assertEquals(ToolEnvelope.CODE_UNSUPPORTED, error.code());
        } finally {
            datasources.close();
        }
    }

    @Test
    void invalidInputIsRejectedBeforeConnecting() {
        // A PostgreSQL-typed URL with the H2 driver: structure is recognized,
        // but nothing can connect — so only pre-connection validation can pass.
        DatasourceManager datasources = H2Sources.manager("jdbc:postgresql://localhost/nope");
        try {
            ExplainSqlHandler handler = new ExplainSqlHandler(datasources, new QueryExecutor(datasources),
                    new StatementParser(datasources));

            assertEquals(ToolEnvelope.CODE_INVALID_INPUT, assertThrows(ToolError.class,
                    () -> handler.explain("h2db", "SELECT 1; SELECT 2")).code());
            assertEquals(ToolEnvelope.CODE_INVALID_INPUT, assertThrows(ToolError.class,
                    () -> handler.explain("h2db", "EXPLAIN SELECT 1")).code());
            assertEquals(ToolEnvelope.CODE_INVALID_INPUT, assertThrows(ToolError.class,
                    () -> handler.explain("h2db", "")).code());
        } finally {
            datasources.close();
        }
    }
}
