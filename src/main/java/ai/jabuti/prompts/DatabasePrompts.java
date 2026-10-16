package ai.jabuti.prompts;

import java.util.List;
import java.util.stream.Collectors;

import ai.jabuti.config.SourceEntry;
import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.tools.ToolNames;
import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The MCP prompts, declared with {@code @Prompt} annotations. Unlike
 * {@link ai.jabuti.tools.ToolRegistrar} the prompts need no programmatic
 * registration: they are per deployment, not per source, so their names are
 * static and only the source guidance is derived from the configuration, per
 * request.
 *
 * <p>The declaration style is load-bearing. The programmatic
 * {@code PromptManager} API renders every received argument with
 * {@code toString()} before the handler runs, so a {@code prompts/get} whose
 * optional argument arrives as an explicit JSON null — the shape clients send
 * for an unset optional argument — fails with a JSON-RPC internal error
 * (quarkus-mcp-server 2.0.0; tracked upstream at
 * quarkiverse/quarkus-mcp-server#1093). Annotated optional arguments reach
 * the method as a plain {@code null} instead.
 *
 * <p>An optional {@code source} argument disambiguates when several sources
 * are configured.
 */
@Singleton
public class DatabasePrompts {

    private final DatasourceManager datasourceManager;

    @Inject
    public DatabasePrompts(DatasourceManager datasourceManager) {
        this.datasourceManager = datasourceManager;
    }

    /**
     * Guided schema exploration with progressive disclosure.
     */
    @Prompt(name = "explore_database",
            description = "Guided workflow to explore the database schema with progressive disclosure")
    public PromptMessage exploreDatabase(
            @PromptArg(name = "source", required = false,
                    description = "Source id to explore (optional when only one source is configured)") String source) {
        return PromptMessage.withUserRole("""
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
                """.formatted(sourceGuidance(source)));
    }

    /**
     * Mermaid ER diagram; relationships are inferred because foreign keys are
     * not reported yet.
     */
    @Prompt(name = "er_diagram",
            description = "Build a Mermaid entity-relationship diagram of the database schema")
    public PromptMessage erDiagram(
            @PromptArg(name = "source", required = false,
                    description = "Source id to diagram (optional when only one source is configured)") String source) {
        return PromptMessage.withUserRole("""
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
                """.formatted(sourceGuidance(source)));
    }

    /**
     * Tells the model which source to use: the named one, the only one, or the
     * list to choose from when several are configured. {@code requested} is
     * {@code null} or blank when the client left the optional argument unset.
     */
    private String sourceGuidance(String requested) {
        List<SourceEntry> sources = datasourceManager.config().sources();
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
