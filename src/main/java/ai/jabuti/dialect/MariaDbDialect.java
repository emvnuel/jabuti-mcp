package ai.jabuti.dialect;

/**
 * MariaDB shares MySQL's dialect; only the id and URL prefix differ.
 */
final class MariaDbDialect extends MySqlDialect {

    MariaDbDialect() {
        super("MARIADB", "jdbc:mariadb:");
    }
}
