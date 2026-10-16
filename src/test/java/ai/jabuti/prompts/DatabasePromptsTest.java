package ai.jabuti.prompts;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import ai.jabuti.config.JdbcMcpConfig;
import ai.jabuti.config.SourceEntry;
import ai.jabuti.datasource.DatasourceManager;
import io.quarkiverse.mcp.server.PromptMessage;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DatabasePrompts}: both prompts must accept a missing
 * ({@code null}), blank, unknown or valid {@code source} argument and render
 * guidance the model can act on. The wire-level contract — including the
 * explicit JSON {@code null} that used to fail the request — is covered by
 * {@code PromptArgsIT}.
 */
class DatabasePromptsTest {

    @Test
    void exploreDatabaseAcceptsEverySourceShape() {
        DatabasePrompts prompts = new DatabasePrompts(manager("alpha", "beta"));

        String valid = textOf(prompts.exploreDatabase("beta"));
        assertTrue(valid.contains("Use source 'beta'"), valid);
        assertTrue(valid.contains("tool names end with '_beta' when several sources are configured"), valid);

        String missing = textOf(prompts.exploreDatabase(null));
        assertTrue(missing.contains("Available sources: alpha, beta"), missing);

        String blank = textOf(prompts.exploreDatabase("   "));
        assertTrue(blank.contains("Available sources: alpha, beta"), blank);

        String unknown = textOf(prompts.exploreDatabase("unknown"));
        assertTrue(unknown.contains("Source 'unknown' is not configured"), unknown);
        assertTrue(unknown.contains("alpha, beta"), unknown);
    }

    @Test
    void erDiagramAcceptsEverySourceShape() {
        DatabasePrompts prompts = new DatabasePrompts(manager("alpha", "beta"));

        String valid = textOf(prompts.erDiagram("alpha"));
        assertTrue(valid.contains("Use source 'alpha'"), valid);
        assertTrue(valid.contains("tool names end with '_alpha' when several sources are configured"), valid);

        String missing = textOf(prompts.erDiagram(null));
        assertTrue(missing.contains("Available sources: alpha, beta"), missing);

        String blank = textOf(prompts.erDiagram(""));
        assertTrue(blank.contains("Available sources: alpha, beta"), blank);

        String unknown = textOf(prompts.erDiagram("unknown"));
        assertTrue(unknown.contains("Source 'unknown' is not configured"), unknown);
        assertTrue(unknown.contains("alpha, beta"), unknown);
    }

    @Test
    void singleSourceDeploymentUsesTheOnlySource() {
        DatabasePrompts prompts = new DatabasePrompts(manager("h2db"));

        assertTrue(textOf(prompts.exploreDatabase(null)).contains("Use the only configured source: 'h2db'"));
        assertTrue(textOf(prompts.erDiagram(null)).contains("Use the only configured source: 'h2db'"));
        assertTrue(textOf(prompts.exploreDatabase("h2db")).contains("Use source 'h2db'"));
    }

    /**
     * A manager over sources that are never contacted: prompt guidance only
     * reads the configured source ids.
     */
    private static DatasourceManager manager(String... sourceIds) {
        List<SourceEntry> sources = new ArrayList<>();
        for (String id : sourceIds) {
            sources.add(new SourceEntry(id, "jdbc:h2:mem:" + id, "sa", "", "h2-jdbc", null));
        }
        return new DatasourceManager(
                new JdbcMcpConfig(sources, List.of(), List.of(), List.of()),
                driverId -> new org.h2.Driver());
    }

    private static String textOf(PromptMessage message) {
        assertNotNull(message);
        return message.content().asText().text();
    }
}
