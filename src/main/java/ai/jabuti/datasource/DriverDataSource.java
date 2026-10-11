package ai.jabuti.datasource;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * A minimal {@link DataSource} around an already-instantiated
 * {@link Driver} (loaded from its own class loader by the driver resolver),
 * delegating {@code getConnection()} to {@link Driver#connect}. Used
 * programmatically with HikariCP so no driver needs to be on the application
 * classpath or registered with {@code DriverManager}.
 */
public final class DriverDataSource implements DataSource {

    private final Driver driver;
    private final String url;
    private final Properties props;

    private PrintWriter logWriter;
    private int loginTimeout;

    public DriverDataSource(Driver driver, String url, Properties props) {
        this.driver = driver;
        this.url = url;
        this.props = props;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connect(props);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Properties merged = new Properties();
        if (props != null) {
            merged.putAll(props);
        }
        if (username != null) {
            merged.setProperty("user", username);
        }
        if (password != null) {
            merged.setProperty("password", password);
        }
        return connect(merged);
    }

    private Connection connect(Properties properties) throws SQLException {
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new SQLException("Driver " + driver.getClass().getName() + " does not accept URL " + url);
        }
        return connection;
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return driver.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
