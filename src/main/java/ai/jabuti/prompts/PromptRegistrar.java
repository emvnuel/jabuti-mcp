package ai.jabuti.prompts;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ai.jabuti.config.SourceEntry;
import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.tools.ToolNames;
import io.quarkiverse.mcp.server.PromptManager;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.PromptResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Registers the MCP prompts at startup, programmatically like
 * {@link ai.jabuti.tools.ToolRegistrar} (sources only exist at runtime).
 * Prompts are per-deployment, not per-source: an optional {@code source}
 * argument disambiguates when several sources are configured.
 */
@Singleton
public class PromptRegistrar {

    private final PromptManager promptManager;
    private final DatasourceManager datasourceManager;

    @Inject
    public PromptRegistrar(PromptManager promptManager, DatasourceManager datasourceManager) {
        this.promptManager = promptManager;
        this.datasourceManager = datasourceManager;
    }

    /**
     * Registers the prompts; invoked by {@link ai.jabuti.McpBootstrapper}
     * before the stdio transport starts serving requests.
     */
    public void registerAll() {
        registerExploreDatabase();
        registerErDiagram();
    }

    private void registerExploreDatabase() {
        promptManager.newPrompt("explore_database")
                .setDescription("Guided workflow to explore the database schema with progressive disclosure")
                .addArgument("source", "Source id to explore (optional when only one source is configured)", false)
                .setHandler(args -> PromptResponse.withMessages(PromptMessage.withUserRole("""
                        The user wants to explore the database exposed by this MCP server.
                        %s

                        Workflow — pause for the user between steps:
                        1. If a `database_info` tool is available, call it first for dialect hints.
                        2. `search_objects` with detail_level=names to list tables and views (narrow with schema/pattern).
                        3. `search_objects` with detail_level=summary for the tables that matter.
                        4. `search_objects` with detail_level=full for the central tables (columns, indexes, row counts).
                        5. `execute_sql` for small aggregate questions (row counts, a few sample rows).

                        Never modify data. Summarize each step in plain language before moving on, and finish with a
                        short overview: the purpose of each table and anything surprising you found.
                        """.formatted(sourceGuidance(args.args())))))
                .register();
    }

    private void registerErDiagram() {
        promptManager.newPrompt("er_diagram")
                .setDescription("Build a Mermaid entity-relationship diagram of the database schema")
                .addArgument("source", "Source id to diagram (optional when only one source is configured)", false)
                .setHandler(args -> PromptResponse.withMessages(PromptMessage.withUserRole("""
                        Create an entity-relationship diagram of the database exposed by this MCP server.
                        %s

                        Workflow:
                        1. If a `database_info` tool is available, call it first for dialect hints.
                        2. `search_objects` with detail_level=names to list the tables.
                        3. `search_objects` with detail_level=full per table for columns, primary keys and indexes;
                           batch with schema/pattern where possible.
                        4. Render a Mermaid `erDiagram`: entities with their attributes (name + type), and
                           relationships with crow's-foot cardinality. Foreign keys are not reported by the server
                           yet, so infer relationships from column naming (`customer_id` -> `customers.id`), primary
                           keys and unique indexes — and say in one line that relationships are inferred.
                        5. Keep it readable: group related tables and note any columns omitted for brevity.
                        """.formatted(sourceGuidance(args.args())))))
                .register();
    }

    /**
     * Tells the model which source to use: the named one, the only one, or the
     * list to choose from when several are configured.
     *
     * <p>{@code args} may be {@code null} when the client sends no arguments
     * for an optional prompt argument; it is treated like an empty map.
     */
    private String sourceGuidance(Map<String, String> args) {
        List<SourceEntry> sources = datasourceManager.config().sources();
        Map<String, String> arguments = args == null ? Map.of() : args;
        String requested = arguments.get("source");
        if (requested != null && !requested.isBlank()) {
            boolean known = sources.stream().anyMatch(source -> source.id().equals(requested));
            if (known) {
                return "Use source '" + requested + "' (tool names end with '_" + ToolNames.normalizeSourceId(requested)
                        + "' when several sources are configured).";
            }
            return "Source '" + requested + "' is not configured; available sources: " + sourceIds(sources)
                    + ". Ask the user which one to use.";
        }
        if (sources.size() == 1) {
            return "Use the only configured source: '" + sources.get(0).id() + "'.";
        }
        return "Available sources: " + sourceIds(sources)
                + ". Ask the user which one to explore before calling any tool; tool names are suffixed with the source id.";
    }

    private static String sourceIds(List<SourceEntry> sources) {
        return sources.stream().map(SourceEntry::id).collect(Collectors.joining(", "));
    }
}
