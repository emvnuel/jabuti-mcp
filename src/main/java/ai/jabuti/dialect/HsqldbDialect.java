package ai.jabuti.dialect;

/**
 * HyperSQL rejects a FROM-less {@code SELECT 1}; its one-row dual is a derived
 * values table, aliased so the probe stays valid across versions.
 */
final class HsqldbDialect extends Dialect {

    HsqldbDialect() {
        super("HSQLDB", "jdbc:hsqldb:");
    }

    @Override
    public String probeSql() {
        return "SELECT 1 FROM (VALUES(1)) AS dual(dual)";
    }
}
