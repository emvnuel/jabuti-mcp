package ai.jabuti.dialect;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * One engine's JDBC dialect: everything Jabuti customizes per engine — the
 * connection probe, EXPLAIN form, identifier quoting, string escaping, the
 * default search scope and pool sizing. A dialect is selected from the
 * source's JDBC URL alone by {@link Dialects}. Engines without a subclass
 * fall back to this class's portable ANSI behavior.
 *
 * <p>The hierarchy is sealed and the registry is checked by tests, so adding
 * an engine means declaring one subclass in {@code permits} and registering
 * it in {@link Dialects}: neither step can be forgotten silently. Every
 * behavior has a working default, so a subclass overrides only what differs
 * (template method); {@link #explain(String)} must be overridden together
 * with {@link #supportsExplain()}.
 */
public abstract sealed class Dialect
        permits PostgresDialect, MySqlDialect, SqliteDialect, OracleDialect, Db2Dialect, DerbyDialect,
        FirebirdDialect, HanaDialect, HsqldbDialect, InformixDialect, AnsiDialect {

    private final String id;
    private final List<String> urlPrefixes;

    /**
     * @param id the stable identifier reported by {@code database_info}
     * @param urlPrefixes JDBC URL prefixes that select this dialect
     *     (normalized to lower case); an empty list makes it the fallback,
     *     which only serves URLs every other dialect declined
     */
    protected Dialect(String id, String... urlPrefixes) {
        this.id = id;
        this.urlPrefixes = Arrays.stream(urlPrefixes)
                .map(prefix -> prefix.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * The stable identifier reported by {@code database_info} as the
     * {@code family} field (e.g. {@code PG}, {@code DB2}, {@code OTHER}).
     */
    public final String id() {
        return id;
    }

    /**
     * Whether this dialect serves {@code jdbcUrl}, matched by its registered
     * URL prefixes.
     */
    public boolean matches(String jdbcUrl) {
        if (jdbcUrl == null) {
            return false;
        }
        String normalized = jdbcUrl.toLowerCase(Locale.ROOT);
        return urlPrefixes.stream().anyMatch(normalized::startsWith);
    }

    /**
     * The engine's cheapest connection-liveness probe, run whenever a
     * source's pool is used (not only when it is created) and reported by
     * {@code health_check}. Engines whose grammar allows a FROM-less
     * {@code SELECT 1} use it; the others select a constant from their
     * one-row dummy table.
     */
    public String probeSql() {
        return "SELECT 1";
    }

    /**
     * MySQL and MariaDB treat backslash as an escape inside single- and
     * double-quoted strings unless {@code NO_BACKSLASH_ESCAPES} is set; other
     * engines follow the standard where only doubled quotes escape.
     */
    public boolean backslashEscapedStrings() {
        return false;
    }

    /**
     * Quotes an identifier with the engine's delimiter, doubling any embedded
     * delimiter.
     */
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Qualifies a table with its schema when one is given, quoting both with
     * the engine's delimiter.
     */
    public String qualify(String schema, String table) {
        String quotedTable = quote(table);
        return schema == null ? quotedTable : quote(schema) + "." + quotedTable;
    }

    /**
     * Whether this tool has a supported EXPLAIN form for the engine.
     */
    public boolean supportsExplain() {
        return false;
    }

    /**
     * Prefixes the statement with the engine's EXPLAIN form; only called for
     * dialects where {@link #supportsExplain()} is true.
     */
    public String explain(String sql) {
        throw new UnsupportedOperationException("dialect " + id + " has no supported EXPLAIN form");
    }

    /**
     * Scopes a search to the connected database when the caller passes no
     * explicit schema (mirrors dbhub's {@code resolveDefaultSchemas}): by
     * default every schema is searched, and a single {@code null} entry means
     * the engine has no schema concept: queries read without a schema filter.
     *
     * @param schemas the discovered schema list
     * @param currentCatalog supplies the connection's current catalog; only
     *     consulted by catalog-based engines
     */
    public List<String> defaultSchemaScope(List<String> schemas, Supplier<String> currentCatalog) {
        if (schemas.isEmpty()) {
            return Collections.singletonList(null);
        }
        return schemas;
    }

    /**
     * The source's effective pool size; engines may pin it regardless of the
     * configured value.
     */
    public int effectiveMaxPoolSize(int configured) {
        return configured;
    }

    @Override
    public String toString() {
        return id;
    }
}
