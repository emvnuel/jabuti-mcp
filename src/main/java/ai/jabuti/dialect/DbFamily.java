package ai.jabuti.dialect;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Database family, inferred from the JDBC URL alone (no extra configuration).
 * Used only for the few per-database overrides: EXPLAIN syntax, identifier
 * quoting and default-schema scoping.
 */
public enum DbFamily {
    PG, MYSQL, MARIADB, SQLITE, OTHER;

    public static DbFamily fromJdbcUrl(String url) {
        if (url == null) {
            return OTHER;
        }
        String normalized = url.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:postgresql:")) {
            return PG;
        }
        if (normalized.startsWith("jdbc:mysql:")) {
            return MYSQL;
        }
        if (normalized.startsWith("jdbc:mariadb:")) {
            return MARIADB;
        }
        if (normalized.startsWith("jdbc:sqlite:")) {
            return SQLITE;
        }
        return OTHER;
    }

    /**
     * MySQL and MariaDB treat backslash as an escape inside single- and
     * double-quoted strings unless {@code NO_BACKSLASH_ESCAPES} is set; other
     * engines follow the standard where only doubled quotes escape.
     */
    public boolean backslashEscapedStrings() {
        return this == MYSQL || this == MARIADB;
    }

    /**
     * Quotes an identifier with the engine's delimiter, doubling any embedded
     * delimiter.
     */
    public String quote(String identifier) {
        return switch (this) {
            case MYSQL, MARIADB -> "`" + identifier.replace("`", "``") + "`";
            default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
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
        return this != OTHER;
    }

    /**
     * Prefixes the statement with the engine's EXPLAIN form. Bare EXPLAIN on
     * SQLite returns raw VDBE bytecode; EXPLAIN QUERY PLAN is the readable
     * form.
     */
    public String explain(String sql) {
        if (this == SQLITE) {
            return "EXPLAIN QUERY PLAN " + sql;
        }
        return "EXPLAIN " + sql;
    }

    /**
     * Scopes a search to the connected database when the caller passes no
     * explicit schema (mirrors dbhub's {@code resolveDefaultSchemas}): PG
     * prefers {@code public}, catalog-based engines use the current catalog,
     * everything else searches all schemas. A single {@code null} entry means
     * the engine has no schema concept: queries read without a schema filter.
     *
     * @param schemas the discovered schema list
     * @param currentCatalog supplies the connection's current catalog; only
     *     consulted for catalog-based engines
     */
    public List<String> defaultSchemaScope(List<String> schemas, Supplier<String> currentCatalog) {
        switch (this) {
            case PG -> {
                if (schemas.contains("public")) {
                    return List.of("public");
                }
            }
            case MYSQL, MARIADB -> {
                String catalog = currentCatalog.get();
                if (catalog != null) {
                    return List.of(catalog);
                }
            }
        }
        if (schemas.isEmpty()) {
            return Collections.singletonList(null);
        }
        return schemas;
    }
}
