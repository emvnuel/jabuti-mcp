package ai.jabuti.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.jabuti.config.JdbcMcpConfig;
import ai.jabuti.config.SourceEntry;
import ai.jabuti.config.ToolEntry;
import ai.jabuti.datasource.DatasourceManager;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.runtime.Startup;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Registers one programmatic MCP tool per source × enabled tool at startup
 * (sources only exist at runtime, so no {@code @Tool} annotations are used).
 * Single-source deployments keep the bare tool names; multiple sources suffix
 * with the normalized source id. When {@code tools:} is omitted from the YAML,
 * {@code execute_sql} and {@code search_objects} are enabled for every source
 * (the defaults); {@code explain_sql}, {@code health_check} and
 * {@code database_info} are opt-in.
 *
 * <p>Every tool carries a title and MCP annotations: read-only tools set
 * {@code readOnlyHint} so clients can auto-approve them, write-enabled
 * {@code execute_sql} sets {@code destructiveHint}.
 */
@Singleton
public class ToolRegistrar {

    private final ToolManager toolManager;
    private final DatasourceManager datasourceManager;
    private final ExecuteSqlHandler executeSqlHandler;
    private final SearchObjectsHandler searchObjectsHandler;
    private final ExplainSqlHandler explainSqlHandler;
    private final HealthCheckHandler healthCheckHandler;
    private final DatabaseInfoHandler databaseInfoHandler;

    @Inject
    public ToolRegistrar(ToolManager toolManager, DatasourceManager datasourceManager,
            ExecuteSqlHandler executeSqlHandler, SearchObjectsHandler searchObjectsHandler,
            ExplainSqlHandler explainSqlHandler, HealthCheckHandler healthCheckHandler,
            DatabaseInfoHandler databaseInfoHandler) {
        this.toolManager = toolManager;
        this.datasourceManager = datasourceManager;
        this.executeSqlHandler = executeSqlHandler;
        this.searchObjectsHandler = searchObjectsHandler;
        this.explainSqlHandler = explainSqlHandler;
        this.healthCheckHandler = healthCheckHandler;
        this.databaseInfoHandler = databaseInfoHandler;
    }

    @Startup
    void registerAll() {
        JdbcMcpConfig config = datasourceManager.config();
        boolean singleSource = config.sources().size() == 1;
        List<ToolEntry> tools = config.tools().isEmpty() ? defaultTools(config) : config.tools();
        Map<String, String> sourcesByToolName = new HashMap<>();
        for (ToolEntry tool : tools) {
            String name = ToolNames.toolName(tool.name(), tool.source(), singleSource);
            String previousSource = sourcesByToolName.putIfAbsent(name, tool.source());
            if (previousSource != null) {
                throw new IllegalStateException("Sources '" + previousSource + "' and '" + tool.source()
                        + "' both produce the tool name '" + name + "'; rename one of the sources");
            }
            switch (tool.name()) {
                case "execute_sql" -> registerExecuteSql(name, tool, singleSource);
                case "search_objects" -> registerSearchObjects(name, tool, singleSource);
                case "explain_sql" -> registerExplainSql(name, tool, singleSource);
                case "health_check" -> registerHealthCheck(name, tool, singleSource);
                case "database_info" -> registerDatabaseInfo(name, tool, singleSource);
                default -> throw new IllegalStateException("Unknown tool '" + tool.name() + "'");
            }
        }
    }

    private static List<ToolEntry> defaultTools(JdbcMcpConfig config) {
        List<ToolEntry> tools = new ArrayList<>();
        for (SourceEntry source : config.sources()) {
            tools.add(new ToolEntry("execute_sql", source.id(), null, null));
            tools.add(new ToolEntry("search_objects", source.id(), null, null));
        }
        return tools;
    }

    private void registerExecuteSql(String name, ToolEntry tool, boolean singleSource) {
        boolean readonly = Boolean.TRUE.equals(tool.readonly());
        String title = readonly ? "Execute SQL (read-only)" : "Execute SQL";
        String readonlyNote = readonly ? " [READ-ONLY MODE]" : "";
        String maxRowsNote = " (limited to " + tool.maxRows() + " rows; capped results carry \"truncated\": true)";
        String description = "Execute SQL queries on " + target(tool, singleSource) + readonlyNote + maxRowsNote;

        toolManager.newTool(name)
                .setTitle(title)
                .setDescription(description)
                .setAnnotations(annotations(title, readonly, !readonly))
                .addArgument("sql", "SQL to execute (multiple statements separated by ;)", true, String.class)
                .setHandler(ta -> {
                    logFirstUse(ta.log(), tool.source());
                    return ToolCalls.response(ToolEnvelope.CODE_EXECUTION_ERROR,
                            () -> executeSqlHandler.execute(
                                    tool.source(), (String) ta.args().get("sql"), readonly, tool.maxRows()));
                })
                .register();
    }

    private void registerSearchObjects(String name, ToolEntry tool, boolean singleSource) {
        String title = "Search database objects";
        toolManager.newTool(name)
                .setTitle(title)
                .setDescription("Search and list database objects on " + target(tool, singleSource)
                        + ". Start with detail_level=names and drill down with summary/full")
                .setAnnotations(annotations(title, true, false))
                .addArgument("object_type", "Object type to search: schema|table|view|column|procedure|function|index",
                        true, String.class)
                .addArgument("pattern", "LIKE pattern (% = any chars, _ = one char)", false, String.class)
                .addArgument("schema", "Filter to schema", false, String.class)
                .addArgument("table", "Filter to table (requires schema; column/index only)", false, String.class)
                .addArgument("detail_level", "Detail: names (minimal), summary (metadata), full (all)", false, String.class)
                .addArgument("limit", "Max results (default 100, max 1000)", false, Integer.class)
                .setHandler(ta -> {
                    logFirstUse(ta.log(), tool.source());
                    return ToolCalls.response(ToolEnvelope.CODE_SEARCH_ERROR,
                            () -> searchObjectsHandler.search(
                                    tool.source(),
                                    (String) ta.args().get("object_type"),
                                    (String) ta.args().get("pattern"),
                                    (String) ta.args().get("schema"),
                                    (String) ta.args().get("table"),
                                    (String) ta.args().get("detail_level"),
                                    (Integer) ta.args().get("limit")));
                })
                .register();
    }

    private void registerExplainSql(String name, ToolEntry tool, boolean singleSource) {
        String title = "Explain SQL";
        toolManager.newTool(name)
                .setTitle(title)
                .setDescription("Show the execution plan for a SQL statement on " + target(tool, singleSource)
                        + " without running it (always read-only)")
                .setAnnotations(annotations(title, true, false))
                .addArgument("sql", "Single SQL statement to explain (no trailing semicolon-separated statements)",
                        true, String.class)
                .setHandler(ta -> {
                    logFirstUse(ta.log(), tool.source());
                    return ToolCalls.response(ToolEnvelope.CODE_EXECUTION_ERROR,
                            () -> explainSqlHandler.explain(tool.source(), (String) ta.args().get("sql")));
                })
                .register();
    }

    private void registerHealthCheck(String name, ToolEntry tool, boolean singleSource) {
        String title = "Database health check";
        toolManager.newTool(name)
                .setTitle(title)
                .setDescription("Report connection pool and database health metrics for "
                        + target(tool, singleSource) + " (read-only)")
                .setAnnotations(annotations(title, true, false))
                .setHandler(ta -> {
                    logFirstUse(ta.log(), tool.source());
                    return ToolCalls.response(ToolEnvelope.CODE_EXECUTION_ERROR,
                            () -> healthCheckHandler.check(tool.source()));
                })
                .register();
    }

    private void registerDatabaseInfo(String name, ToolEntry tool, boolean singleSource) {
        String title = "Database information";
        toolManager.newTool(name)
                .setTitle(title)
                .setDescription("Report database product, version and dialect hints for " + target(tool, singleSource)
                        + " (identifier quoting, SQL keywords, catalog/schema terms, transaction support); read-only."
                        + " Run this before writing non-trivial SQL.")
                .setAnnotations(annotations(title, true, false))
                .setHandler(ta -> {
                    logFirstUse(ta.log(), tool.source());
                    return ToolCalls.response(ToolEnvelope.CODE_EXECUTION_ERROR,
                            () -> databaseInfoHandler.info(tool.source()));
                })
                .register();
    }

    /**
     * Sends a client-visible MCP log notification when the source has no pool
     * yet: the first use resolves the driver from Maven and may take a while,
     * which otherwise looks like a hang.
     */
    private void logFirstUse(McpLog log, String sourceId) {
        if (!datasourceManager.isConnected(sourceId)) {
            log.info("Source '%s' is not connected yet; the first use may download its driver from Maven", sourceId);
        }
    }

    /**
     * Tool annotations for the MCP wire: the database is an external system
     * ({@code openWorldHint}), idempotency is not advertised for either read or
     * write paths.
     */
    private static ToolManager.ToolAnnotations annotations(String title, boolean readonly, boolean destructive) {
        return new ToolManager.ToolAnnotations(title, readonly, destructive, false, true);
    }

    private static String target(ToolEntry tool, boolean singleSource) {
        return singleSource ? "the database" : "'" + tool.source() + "' database";
    }
}
