package ai.jabuti.dialect;

import java.util.List;
import java.util.function.Supplier;

/**
 * PostgreSQL: FROM-less {@code SELECT 1}, plain EXPLAIN, ANSI quoting and the
 * {@code public} schema preferred as the default search scope.
 */
final class PostgresDialect extends Dialect {

    PostgresDialect() {
        super("PG", "jdbc:postgresql:");
    }

    @Override
    public boolean supportsExplain() {
        return true;
    }

    @Override
    public String explain(String sql) {
        return "EXPLAIN " + sql;
    }

    @Override
    public List<String> defaultSchemaScope(List<String> schemas, Supplier<String> currentCatalog) {
        if (schemas.contains("public")) {
            return List.of("public");
        }
        return super.defaultSchemaScope(schemas, currentCatalog);
    }
}
