package ai.jabuti.jdbc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ai.jabuti.datasource.DatasourceManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Splits a tool call's SQL into a {@link StatementBatch} using the source's
 * lexer rules (MySQL/MariaDB honor backslash escapes in strings, so the
 * read-only gate and the executor see the same statements as the server).
 * The lexer rules are resolved once per source and cached; parsing never
 * opens a connection, but the source must exist in the configuration.
 */
@Singleton
public class StatementParser {

    private final DatasourceManager datasourceManager;
    private final Map<String, Boolean> backslashEscapesBySource = new ConcurrentHashMap<>();

    @Inject
    public StatementParser(DatasourceManager datasourceManager) {
        this.datasourceManager = datasourceManager;
    }

    public StatementBatch parse(String sourceId, String sql) {
        boolean backslashEscapes = backslashEscapes(sourceId);
        List<Statement> statements = SqlSplitter.split(sql, backslashEscapes).stream()
                .map(text -> Statement.of(text, backslashEscapes))
                .toList();
        return new StatementBatch(sourceId, statements);
    }

    private boolean backslashEscapes(String sourceId) {
        return backslashEscapesBySource.computeIfAbsent(sourceId, datasourceManager::backslashEscapedStrings);
    }
}
