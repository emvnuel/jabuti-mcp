package ai.jabuti.testsupport;

import java.util.List;
import java.util.UUID;

import ai.jabuti.config.JdbcMcpConfig;
import ai.jabuti.config.SourceEntry;
import ai.jabuti.datasource.DatasourceManager;

/**
 * A {@link DatasourceManager} over an in-memory H2 database with the fixed
 * source id {@code h2db}. The caller owns the manager and must close it.
 */
public final class H2Sources {

    private H2Sources() {
    }

    public static DatasourceManager uniqueManager() {
        return manager("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    /**
     * A manager whose single source uses {@code url} but still loads the H2
     * driver. Nothing connects until a test asks for a connection, so
     * dialect-sensitive logic (statement splitting, EXPLAIN support) can be
     * exercised for another database family without that database.
     */
    public static DatasourceManager manager(String url) {
        JdbcMcpConfig config = new JdbcMcpConfig(
                List.of(new SourceEntry("h2db", url, "sa", "", "h2-jdbc", null)),
                List.of(),
                List.of(),
                List.of());
        return new DatasourceManager(config, driverId -> new org.h2.Driver());
    }
}
