package ai.jabuti.dialect;

/**
 * SAP HANA: SELECT requires a FROM clause, so the probe reads the one-row
 * {@code DUMMY} table; the driver connects with {@code jdbc:sap:}.
 */
final class HanaDialect extends Dialect {

    HanaDialect() {
        super("HANA", "jdbc:sap:");
    }

    @Override
    public String probeSql() {
        return "SELECT 1 FROM DUMMY";
    }
}
