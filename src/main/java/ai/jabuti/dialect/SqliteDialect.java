package ai.jabuti.dialect;

/**
 * SQLite: FROM-less {@code SELECT 1}; bare EXPLAIN returns raw VDBE bytecode,
 * so the readable EXPLAIN QUERY PLAN is used; the pool is pinned to one
 * connection because a bigger pool breaks {@code :memory:} databases (each
 * connection gets its own database) and file access serializes anyway.
 */
final class SqliteDialect extends Dialect {

    SqliteDialect() {
        super("SQLITE", "jdbc:sqlite:");
    }

    @Override
    public boolean supportsExplain() {
        return true;
    }

    @Override
    public String explain(String sql) {
        return "EXPLAIN QUERY PLAN " + sql;
    }

    @Override
    public int effectiveMaxPoolSize(int configured) {
        return 1;
    }
}
