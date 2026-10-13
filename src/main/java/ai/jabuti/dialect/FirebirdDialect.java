package ai.jabuti.dialect;

/**
 * Firebird: SELECT requires a FROM clause, so the probe reads the one-row
 * {@code RDB$DATABASE} system table.
 */
final class FirebirdDialect extends Dialect {

    FirebirdDialect() {
        super("FIREBIRD", "jdbc:firebirdsql:");
    }

    @Override
    public String probeSql() {
        return "SELECT 1 FROM RDB$DATABASE";
    }
}
