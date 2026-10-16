package ai.jabuti;

import java.io.OutputStream;
import java.io.PrintStream;

import ai.jabuti.tools.ToolRegistrar;
import io.quarkiverse.mcp.server.stdio.runtime.StdioMcpMessageHandler;
import io.quarkus.runtime.Startup;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Boots the MCP server in a deterministic order: tools are registered first,
 * and only then the stdio transport starts reading {@code System.in}. The
 * prompts need no registration here: they are annotated (see
 * {@link ai.jabuti.prompts.DatabasePrompts}), so the extension knows them from
 * build-time metadata as soon as its beans are created — before this observer
 * runs.
 *
 * <p>With the extension's automatic initialization ({@code
 * quarkus.mcp.server.stdio.initialization-enabled=true}) the transport starts
 * on its own RUNTIME_INIT step, concurrently with this application's
 * {@code StartupEvent} observers. Under load an early {@code tools/list} can
 * race {@link ToolRegistrar#registerAll()} and observe an empty or partial
 * tool list &#8212; which clients cache for the whole connection. Registering
 * explicitly closes that window by construction.
 */
@Singleton
public class McpBootstrapper {

    private static final Logger LOG = Logger.getLogger(McpBootstrapper.class);

    private final ToolRegistrar toolRegistrar;
    private final StdioMcpMessageHandler stdioHandler;
    private final boolean stdioEnabled;

    @Inject
    public McpBootstrapper(ToolRegistrar toolRegistrar,
            StdioMcpMessageHandler stdioHandler,
            @ConfigProperty(name = "quarkus.mcp.server.stdio.enabled", defaultValue = "true") boolean stdioEnabled) {
        this.toolRegistrar = toolRegistrar;
        this.stdioHandler = stdioHandler;
        this.stdioEnabled = stdioEnabled;
    }

    @Startup
    void boot() {
        toolRegistrar.registerAll();
        if (!stdioEnabled) {
            LOG.info("stdio transport is disabled; no MCP messages are read or written");
            return;
        }
        PrintStream stdout = System.out;
        try {
            // stdout is the MCP channel: mute it before anything can print
            // there, mirroring the extension default (null-system-out=true)
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            stdioHandler.initialize(stdout);
        } catch (RuntimeException e) {
            System.setOut(stdout);
            LOG.error("Unable to start the MCP stdio transport", e);
            throw e;
        }
    }
}
