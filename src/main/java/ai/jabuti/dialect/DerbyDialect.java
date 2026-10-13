package ai.jabuti.dialect;

/**
 * Apache Derby: SQL requires a FROM clause and its IBM lineage provides
 * {@code SYSIBM.SYSDUMMY1} for the probe.
 */
final class DerbyDialect extends Dialect {

    DerbyDialect() {
        super("DERBY", "jdbc:derby:");
    }

    @Override
    public String probeSql() {
        return "SELECT 1 FROM SYSIBM.SYSDUMMY1";
    }
}
