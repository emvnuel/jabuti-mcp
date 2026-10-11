package ai.jabuti.datasource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import ai.jabuti.config.JdbcMcpConfig;
import ai.jabuti.config.JdbcMcpConfigProvider;
import ai.jabuti.config.SourceEntry;
import ai.jabuti.dialect.DbFamily;
import ai.jabuti.driver.DriverRegistry;
import ai.jabuti.driver.MavenDriverResolver;
import ai.jabuti.safety.DsnRedactor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Owns one programmatic HikariCP pool per configured source, created lazily on
 * first use. Agroal is intentionally not used: sources only exist at runtime.
 */
@Singleton
public class DatasourceManager {

    public static final int CONNECTION_TIMEOUT_MS = 30_000;

    private final JdbcMcpConfig config;
    private final Function<String, Driver> driverLoader;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    @Inject
    public DatasourceManager(JdbcMcpConfigProvider provider, DriverRegistry registry, MavenDriverResolver resolver) {
        this(provider.config(), driverId -> resolver.loadDriver(registry.require(driverId)));
    }

    public DatasourceManager(JdbcMcpConfig config, Function<String, Driver> driverLoader) {
        this.config = config;
        this.driverLoader = driverLoader;
    }

    public JdbcMcpConfig config() {
        return config;
    }

    /**
     * Creates the pool for the source if needed and probes it with
     * {@code SELECT 1}. A failed probe drops the pool so a later retry can
     * rebuild it.
     */
    public void ensureConnected(String sourceId) {
        connect(config.requireSource(sourceId));
    }

    /**
     * Returns the pool for the source, creating it on first access.
     */
    public HikariDataSource poolFor(String sourceId) {
        return pool(config.requireSource(sourceId));
    }

    /**
     * Whether a pool for the source already exists (used to warn about the
     * first call's driver download).
     */
    public boolean isConnected(String sourceId) {
        return pools.containsKey(sourceId);
    }

    /**
     * The database family inferred from the source's JDBC URL.
     */
    public DbFamily family(String sourceId) {
        return DbFamily.fromJdbcUrl(config.requireSource(sourceId).url());
    }

    /**
     * Whether the source's engine treats backslash as a string escape.
     */
    public boolean backslashEscapedStrings(String sourceId) {
        return family(sourceId).backslashEscapedStrings();
    }

    /**
     * The source's pool gauges; missing JMX gauges degrade to {@code 0}.
     */
    public PoolMetrics poolMetrics(String sourceId) {
        HikariDataSource pool = poolFor(sourceId);
        HikariPoolMXBean poolBean = pool.getHikariPoolMXBean();
        return new PoolMetrics(
                poolBean != null ? poolBean.getTotalConnections() : 0,
                poolBean != null ? poolBean.getActiveConnections() : 0,
                poolBean != null ? poolBean.getIdleConnections() : 0,
                pool.getMaximumPoolSize());
    }

    /**
     * Borrows a connection and reads the database product/version plus a
     * {@code SELECT 1} probe; each failed read degrades to a note instead of
     * failing the check.
     */
    public DatabaseInfo databaseInfo(String sourceId) {
        SourceEntry source = config.requireSource(sourceId);
        List<String> notes = new ArrayList<>();
        String product = null;
        String version = null;
        boolean selectOneOk = false;
        try (Connection connection = pool(source).getConnection()) {
            try {
                DatabaseMetaData metaData = connection.getMetaData();
                product = metaData.getDatabaseProductName();
                version = metaData.getDatabaseProductVersion();
            } catch (Exception e) {
                notes.add("database product/version unavailable: " + e.getMessage());
            }
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                selectOneOk = resultSet.next();
            } catch (Exception e) {
                notes.add("SELECT 1 probe failed: " + e.getMessage());
            }
        } catch (SQLException e) {
            throw new IllegalStateException(DsnRedactor.redact(e.getMessage(), source.password()), e);
        }
        return new DatabaseInfo(product, version, selectOneOk, notes);
    }

    /**
     * Borrows a connection and reads the engine's dialect hints for the LLM:
     * product/version, driver, identifier quoting, catalog/schema terms,
     * transaction support and SQL keywords. Each failed read degrades to a
     * note instead of failing the whole call; the URL is redacted.
     */
    public DialectInfo dialectInfo(String sourceId) {
        SourceEntry source = config.requireSource(sourceId);
        List<String> notes = new ArrayList<>();
        try (Connection connection = pool(source).getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return new DialectInfo(
                    read(metaData::getDatabaseProductName, "database product", notes),
                    read(metaData::getDatabaseProductVersion, "database version", notes),
                    read(metaData::getDriverName, "driver name", notes),
                    read(metaData::getDriverVersion, "driver version", notes),
                    DsnRedactor.redactUrl(source.url()),
                    read(metaData::getIdentifierQuoteString, "identifier quote", notes),
                    read(metaData::getCatalogTerm, "catalog term", notes),
                    read(metaData::getSchemaTerm, "schema term", notes),
                    read(metaData::isReadOnly, "read-only flag", notes),
                    read(metaData::supportsTransactions, "transaction support", notes),
                    read(metaData::getMaxConnections, "max connections", notes),
                    read(metaData::getSQLKeywords, "SQL keywords", notes),
                    notes);
        } catch (SQLException e) {
            throw new IllegalStateException(DsnRedactor.redact(e.getMessage(), source.password()), e);
        }
    }

    private static <T> T read(MetadataCall<T> call, String label, List<String> notes) {
        try {
            return call.get();
        } catch (Exception e) {
            notes.add(label + " unavailable: " + e.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    private interface MetadataCall<T> {
        T get() throws Exception;
    }

    /**
     * Borrows a connection from the source's pool and runs {@code call} with
     * it, redacting the source password from any {@link SQLException} message.
     */
    public <T> T withConnection(String sourceId, ConnectionCall<T> call) {
        SourceEntry source = config.requireSource(sourceId);
        try (Connection connection = connect(source).getConnection()) {
            return call.apply(connection);
        } catch (SQLException e) {
            throw new IllegalStateException(DsnRedactor.redact(e.getMessage(), source.password()), e);
        }
    }

    private HikariDataSource connect(SourceEntry source) {
        HikariDataSource pool = pool(source);
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        } catch (SQLException e) {
            HikariDataSource broken = pools.remove(source.id());
            if (broken != null) {
                broken.close();
            }
            throw new IllegalStateException("Unable to connect to source '" + source.id() + "' ("
                    + DsnRedactor.redactUrl(source.url()) + "): "
                    + DsnRedactor.redact(e.getMessage(), source.password()), e);
        }
        return pool;
    }

    private HikariDataSource pool(SourceEntry source) {
        return pools.computeIfAbsent(source.id(), id -> createPool(source));
    }

    @PreDestroy
    public void close() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    private HikariDataSource createPool(SourceEntry source) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("jabuti-mcp-" + source.id());
        hikari.setDataSource(new DriverDataSource(driverLoader.apply(source.driver()), source.url(), credentials(source)));
        hikari.setMaximumPoolSize(effectiveMaxPoolSize(source.url(), source.poolMaxConnections()));
        hikari.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        // Failures surface through ensureConnected's probe with a redacted,
        // source-scoped message instead of a PoolInitializationException.
        hikari.setInitializationFailTimeout(-1);
        return new HikariDataSource(hikari);
    }

    private static Properties credentials(SourceEntry source) {
        Properties props = new Properties();
        if (source.username() != null) {
            props.setProperty("user", source.username());
        }
        if (source.password() != null) {
            props.setProperty("password", source.password());
        }
        return props;
    }

    /**
     * SQLite is locked to a single connection: a bigger pool breaks
     * {@code :memory:} databases (each connection gets its own database) and
     * serializes file access anyway.
     */
    static int effectiveMaxPoolSize(String url, int configured) {
        return DbFamily.fromJdbcUrl(url) == DbFamily.SQLITE ? 1 : configured;
    }

    /**
     * A snapshot of one pool's connection gauges.
     */
    public record PoolMetrics(int total, int active, int idle, int max) {

        /**
         * The pool gauges' wire shape ({@code total/active/idle/max}).
         */
        public Map<String, Object> toPayload() {
            Map<String, Object> connections = new LinkedHashMap<>();
            connections.put("total", total);
            connections.put("active", active);
            connections.put("idle", idle);
            connections.put("max", max);
            return connections;
        }
    }

    /**
     * The source's database identity plus the health probe result; failed
     * reads are reported through {@code notes} instead of failing.
     */
    public record DatabaseInfo(String product, String version, boolean selectOneOk, List<String> notes) {
    }

    /**
     * Dialect hints read from {@link DatabaseMetaData} for the LLM; nullable
     * fields mean the engine's driver did not provide the value (see
     * {@code notes}). The URL is already redacted.
     */
    public record DialectInfo(String product, String version, String driverName, String driverVersion, String url,
            String identifierQuote, String catalogTerm, String schemaTerm, Boolean readOnly,
            Boolean supportsTransactions, Integer maxConnections, String sqlKeywords, List<String> notes) {
    }

    @FunctionalInterface
    public interface ConnectionCall<T> {
        T apply(Connection connection) throws SQLException;
    }
}
