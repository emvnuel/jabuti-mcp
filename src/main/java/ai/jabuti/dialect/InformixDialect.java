package ai.jabuti.dialect;

/**
 * Informix: the probe reads one constant from the always-populated
 * {@code systables} catalog ({@code tabid = 1} is the catalog's own row).
 */
final class InformixDialect extends Dialect {

    InformixDialect() {
        super("INFORMIX", "jdbc:informix-sqli:");
    }

    @Override
    public String probeSql() {
        return "SELECT 1 FROM systables WHERE tabid = 1";
    }
}
