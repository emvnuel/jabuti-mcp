package ai.jabuti.config;

import java.nio.file.Path;

import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Loads the YAML configuration exactly once at startup and exposes it to the
 * rest of the application. The path comes from {@code jabuti.config}
 * (declared in application.properties, overridable via the {@code JABUTI_CONFIG}
 * environment variable or {@code -Djabuti.config=...}) and is resolved
 * against the working directory.
 */
@Singleton
public class JdbcMcpConfigProvider {

    private final JdbcMcpConfig config;

    public JdbcMcpConfigProvider(
            @ConfigProperty(name = DatasourceConfigLoader.CONFIG_PATH_PROPERTY, defaultValue = "datasources.yaml") String configuredPath) {
        this.config = new DatasourceConfigLoader().load(Path.of(configuredPath));
    }

    public JdbcMcpConfig config() {
        return config;
    }
}
