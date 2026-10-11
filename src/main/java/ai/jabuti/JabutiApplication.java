package ai.jabuti;

import java.util.ArrayList;
import java.util.List;

import ai.jabuti.config.DatasourceConfigLoader;
import ai.jabuti.driver.MavenDriverResolver;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * The server's entry point: translates the CLI flags into system properties
 * before Quarkus boots, then hands the remaining arguments to Quarkus.
 * Mirrors the shared launcher pattern of quarkus-mcp-servers.
 *
 * <ul>
 *   <li>{@code --config=/path/to/datasources.yaml} — same as
 *       {@code -Djabuti.config=...}</li>
 *   <li>{@code --offline} — same as {@code -Djabuti.offline=true}</li>
 *   <li>{@code --debug} — MCP traffic logging plus DEBUG client logging</li>
 *   <li>{@code --help} / {@code -h} — usage on stderr (stdout is the MCP
 *       channel)</li>
 * </ul>
 */
@QuarkusMain
public class JabutiApplication {

    private static final String USAGE = """
            jabuti-mcp — generic JDBC MCP server (stdio)

            Usage: java -jar jabuti-mcp.jar [options]

              --config=<path>   datasources.yaml to load (default: ./datasources.yaml;
                                also settable with JABUTI_CONFIG or -Djabuti.config=...)
              --offline         resolve drivers from the cache only (same as -Djabuti.offline=true)
              --debug           log MCP traffic and DEBUG client logging (same as
                                -Dquarkus.mcp.server.traffic-logging.enabled=true)
              --help, -h        show this help on stderr

            Passwords should come from the environment via ${VAR} interpolation in the YAML.
            """;

    public static void main(String... args) {
        List<String> remaining = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                System.err.println(USAGE);
                System.exit(0);
            } else if (arg.startsWith("--config=")) {
                System.setProperty(DatasourceConfigLoader.CONFIG_PATH_PROPERTY, arg.substring("--config=".length()));
            } else if (arg.equals("--offline")) {
                System.setProperty(MavenDriverResolver.OFFLINE_PROPERTY, "true");
            } else if (arg.equals("--debug")) {
                System.setProperty("quarkus.mcp.server.traffic-logging.enabled", "true");
                System.setProperty("quarkus.mcp.server.client-logging.default-level", "DEBUG");
            } else {
                remaining.add(arg);
            }
        }
        Quarkus.run(null, (exitCode, exception) -> {
            if (exception != null) {
                exception.printStackTrace();
            }
            if (!LaunchMode.current().isDev()) {
                System.exit(exitCode);
            }
        }, remaining.toArray(String[]::new));
    }
}
