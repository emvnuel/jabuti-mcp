package ai.jabuti.dialect;

import java.util.List;
import java.util.function.Supplier;

/**
 * MySQL: backtick identifiers, backslash string escapes, plain EXPLAIN and
 * the current catalog as the default search scope. Also the base for
 * {@link MariaDbDialect}, which shares every behavior and differs only in id
 * and URL prefix.
 */
sealed class MySqlDialect extends Dialect permits MariaDbDialect {

    MySqlDialect() {
        this("MYSQL", "jdbc:mysql:");
    }

    MySqlDialect(String id, String... urlPrefixes) {
        super(id, urlPrefixes);
    }

    @Override
    public boolean backslashEscapedStrings() {
        return true;
    }

    @Override
    public String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
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
        String catalog = currentCatalog.get();
        if (catalog != null) {
            return List.of(catalog);
        }
        return super.defaultSchemaScope(schemas, currentCatalog);
    }
}
