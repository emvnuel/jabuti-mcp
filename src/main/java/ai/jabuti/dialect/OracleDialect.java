package ai.jabuti.dialect;

/**
 * Oracle: a FROM-less {@code SELECT} is not valid before 23c, so the probe
 * reads the one-row {@code DUAL} table.
 */
final class OracleDialect extends Dialect {

    OracleDialect() {
        super("ORACLE", "jdbc:oracle:");
    }

    @Override
    public String probeSql() {
        return "SELECT 1 FROM DUAL";
    }
}
