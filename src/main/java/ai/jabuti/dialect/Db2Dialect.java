package ai.jabuti.dialect;

/**
 * DB2: SQL requires a FROM clause, so the probe reads
 * {@code SYSIBM.SYSDUMMY1}. Covers DB2 on LUW and z/OS ({@code jdbc:db2:})
 * and DB2 for i, where JTOpen connects with {@code jdbc:as400:}.
 */
final class Db2Dialect extends Dialect {

    Db2Dialect() {
        super("DB2", "jdbc:db2:", "jdbc:as400:");
    }

    @Override
    public String probeSql() {
        return "SELECT 1 FROM SYSIBM.SYSDUMMY1";
    }
}
