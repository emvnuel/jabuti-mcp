package ai.jabuti.prompts;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import ai.jabuti.config.JdbcMcpConfig;
import ai.jabuti.config.SourceEntry;
import ai.jabuti.datasource.DatasourceManager;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Content;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Meta;
import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.PromptManager;
import io.quarkiverse.mcp.server.PromptManager.PromptArguments;
import io.quarkiverse.mcp.server.PromptManager.PromptDefinition;
import io.quarkiverse.mcp.server.PromptManager.PromptInfo;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.PromptResponse;
import io.quarkiverse.mcp.server.RawMessage;
import io.quarkiverse.mcp.server.RequestId;
import io.quarkiverse.mcp.server.Roots;
import io.quarkiverse.mcp.server.Icon;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.TransportHint;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PromptRegistrar}: the registered prompt handlers must
 * accept a missing, blank, unknown or valid {@code source} argument without
 * throwing, and both prompts must produce guidance that the model can act on.
 */
class PromptRegistrarTest {

    @Test
    void exploreDatabaseAcceptsSourceArgument() {
        Function<PromptArguments, PromptResponse> explore = registerAndGet("explore_database", "alpha", "beta");

        String valid = textOf(explore.apply(args("beta")));
        assertTrue(valid.contains("Use source 'beta'"), valid);
        assertTrue(valid.contains("tool names end with '_beta' when several sources are configured"), valid);

        String none = textOf(explore.apply(new FakePromptArguments(null)));
        assertTrue(none.contains("Available sources: alpha, beta"), none);

        String blank = textOf(explore.apply(args("   ")));
        assertTrue(blank.contains("Available sources: alpha, beta"), blank);

        String unknown = textOf(explore.apply(args("unknown")));
        assertTrue(unknown.contains("Source 'unknown' is not configured"), unknown);
        assertTrue(unknown.contains("alpha, beta"), unknown);
    }

    @Test
    void erDiagramAcceptsSourceArgument() {
        Function<PromptArguments, PromptResponse> diagram = registerAndGet("er_diagram", "alpha", "beta");

        String valid = textOf(diagram.apply(args("beta")));
        assertTrue(valid.contains("Use source 'beta'"), valid);
        assertTrue(valid.contains("tool names end with '_beta' when several sources are configured"), valid);

        String none = textOf(diagram.apply(new FakePromptArguments(null)));
        assertTrue(none.contains("Available sources: alpha, beta"), none);

        String blank = textOf(diagram.apply(args("")));
        assertTrue(blank.contains("Available sources: alpha, beta"), blank);

        String unknown = textOf(diagram.apply(args("unknown")));
        assertTrue(unknown.contains("Source 'unknown' is not configured"), unknown);
        assertTrue(unknown.contains("alpha, beta"), unknown);
    }

    @Test
    void singleSourceDeploymentUsesTheOnlySource() {
        Function<PromptArguments, PromptResponse> explore = registerAndGet("explore_database", "h2db");

        String text = textOf(explore.apply(new FakePromptArguments(null)));
        assertTrue(text.contains("Use the only configured source: 'h2db'"), text);
    }

    private static Function<PromptArguments, PromptResponse> registerAndGet(String promptName,
            String... sourceIds) {
        Map<String, Function<PromptArguments, PromptResponse>> handlers = new HashMap<>();
        DatasourceManager manager = manager(sourceIds);
        PromptRegistrar registrar = new PromptRegistrar(fakeManager(handlers), manager);
        registrar.registerAll();
        Function<PromptArguments, PromptResponse> handler = handlers.get(promptName);
        assertNotNull(handler, promptName + " handler was not registered");
        return handler;
    }

    private static DatasourceManager manager(String... sourceIds) {
        List<SourceEntry> sources = new ArrayList<>();
        for (String id : sourceIds) {
            sources.add(new SourceEntry(id, "jdbc:h2:mem:" + id, "sa", "", "h2-jdbc", null));
        }
        return new DatasourceManager(
                new JdbcMcpConfig(sources, List.of(), List.of(), List.of()),
                driverId -> new org.h2.Driver());
    }

    private static PromptArguments args(String source) {
        return new FakePromptArguments(Map.of("source", source));
    }

    private static PromptManager fakeManager(Map<String, Function<PromptArguments, PromptResponse>> handlers) {
        return new PromptManager() {
            @Override
            public PromptInfo getPrompt(String name, String serverName) {
                return null;
            }

            @Override
            public PromptInfo getPrompt(String name) {
                return null;
            }

            @Override
            public PromptDefinition newPrompt(String name) {
                return new FakePromptDefinition(name, handlers);
            }

            @Override
            public PromptInfo removePrompt(String name, String serverName) {
                return null;
            }

            @Override
            public PromptInfo removePrompt(String name) {
                return null;
            }

            @Override
            public void notifyListChanged(Predicate<McpConnection> predicate) {
            }

            @Override
            public Iterator<PromptInfo> iterator() {
                return List.<PromptInfo>of().iterator();
            }
        };
    }

    private static String textOf(PromptResponse response) {
        assertNotNull(response);
        PromptMessage message = response.firstMessage();
        assertNotNull(message);
        Content content = message.content();
        assertNotNull(content);
        return content.asText().text();
    }

    private static final class FakePromptDefinition implements PromptDefinition {

        private final String name;
        private final Map<String, Function<PromptArguments, PromptResponse>> handlers;

        FakePromptDefinition(String name, Map<String, Function<PromptArguments, PromptResponse>> handlers) {
            this.name = name;
            this.handlers = handlers;
        }

        @Override
        public PromptDefinition setTitle(String title) {
            return this;
        }

        @Override
        public PromptDefinition setDescription(String description) {
            return this;
        }

        @Override
        public PromptDefinition setServerName(String serverName) {
            return this;
        }

        @Override
        public PromptDefinition setServerNames(String... serverNames) {
            return this;
        }

        @Override
        public PromptDefinition setMetadata(Map<MetaKey, Object> metadata) {
            return this;
        }

        @Override
        public PromptDefinition addArgument(String name, String title, String description, boolean required,
                String defaultValue) {
            return this;
        }

        @Override
        public PromptDefinition setHandler(Function<PromptArguments, PromptResponse> handler,
                boolean runOnVirtualThread) {
            handlers.put(name, handler);
            return this;
        }

        @Override
        public PromptDefinition setAsyncHandler(Function<PromptArguments, Uni<PromptResponse>> handler) {
            return this;
        }

        @Override
        public PromptDefinition setIcons(Icon... icons) {
            return this;
        }

        @Override
        public PromptDefinition addHint(TransportHint hint) {
            return this;
        }

        @Override
        public PromptDefinition addHint(TransportHint hint, Object value) {
            return this;
        }

        @Override
        public PromptInfo register() {
            return null;
        }
    }

    private record FakePromptArguments(Map<String, String> args) implements PromptArguments {

        @Override
        public RequestId requestId() {
            return null;
        }

        @Override
        public Progress progress() {
            return null;
        }

        @Override
        public Cancellation cancellation() {
            return null;
        }

        @Override
        public McpConnection connection() {
            return null;
        }

        @Override
        public McpLog log() {
            return null;
        }

        @Override
        public Roots roots() {
            return null;
        }

        @Override
        public Sampling sampling() {
            return null;
        }

        @Override
        public Elicitation elicitation() {
            return null;
        }

        @Override
        public RawMessage rawMessage() {
            return null;
        }

        @Override
        public Meta meta() {
            return null;
        }
    }
}
