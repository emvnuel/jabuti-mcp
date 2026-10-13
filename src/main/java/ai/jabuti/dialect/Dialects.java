package ai.jabuti.dialect;

import java.util.List;

/**
 * The built-in dialect registry: resolves a JDBC URL to its engine dialect.
 * Registering a new engine is one entry in the built-in list plus its class;
 * no existing dialect changes (open/closed). Built-in URL prefixes are
 * disjoint, so the resolution order carries no meaning.
 */
public final class Dialects {

    private static final List<Dialect> BUILT_IN = List.of(
            new PostgresDialect(),
            new MySqlDialect(),
            new MariaDbDialect(),
            new SqliteDialect(),
            new OracleDialect(),
            new Db2Dialect(),
            new DerbyDialect(),
            new FirebirdDialect(),
            new HanaDialect(),
            new HsqldbDialect(),
            new InformixDialect());

    private static final Dialect FALLBACK = new AnsiDialect();

    private Dialects() {
    }

    /**
     * The dialect for {@code jdbcUrl}; engines without a built-in dialect get
     * the portable ANSI fallback.
     */
    public static Dialect fromJdbcUrl(String url) {
        return BUILT_IN.stream()
                .filter(dialect -> dialect.matches(url))
                .findFirst()
                .orElse(FALLBACK);
    }

    /**
     * The built-in dialects in resolution order (package-visible for tests).
     */
    static List<Dialect> builtIn() {
        return BUILT_IN;
    }

    /**
     * The portable fallback served for unknown URLs (package-visible for
     * tests).
     */
    static Dialect fallback() {
        return FALLBACK;
    }
}
