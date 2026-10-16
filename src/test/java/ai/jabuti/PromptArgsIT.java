package ai.jabuti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Prompt arguments over the wire, without Docker: boots the packaged server
 * over stdio with two sources that are never contacted (prompts only read the
 * configured source ids) and renders both prompts for every argument shape a
 * client sends: a valid, a blank, an unknown, an explicit {@code null} source
 * and no {@code arguments} at all.
 *
 * <p>The explicit-null shape is the regression this test guards: clients that
 * serialize an unset optional argument as JSON {@code null} must still get the
 * prompt, not a JSON-RPC internal error.
 */
class PromptArgsIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static StdioServerHarness client;

    @BeforeAll
    static void startServer() throws Exception {
        Path tempDir = Files.createTempDirectory("jabuti-mcp-prompt-it");
        Path configFile = tempDir.resolve("datasources.yaml");
        Files.writeString(configFile, """
                sources:
                  - id: pg
                    url: "jdbc:postgresql://127.0.0.1:1/app"
                    username: app
                    password: app
                    driver: postgres-jdbc
                  - id: my
                    url: "jdbc:mysql://127.0.0.1:1/app"
                    username: app
                    password: app
                    driver: mysql-jdbc
                """);

        client = StdioServerHarness.start(configFile, tempDir.resolve("drivers"), "prompt-args-it");
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void listsBothPromptsWithAnOptionalSourceArgument() throws Exception {
        JsonNode prompts = client.rpc("prompts/list", Map.of()).path("result").path("prompts");
        List<String> names = new ArrayList<>();
        prompts.forEach(prompt -> names.add(prompt.get("name").asText()));
        assertTrue(names.containsAll(List.of("explore_database", "er_diagram")), names.toString());

        for (String name : List.of("explore_database", "er_diagram")) {
            JsonNode source = promptByName(prompts, name).path("arguments").get(0);
            assertEquals("source", source.path("name").asText(), name);
            assertFalse(source.path("required").asBoolean(true),
                    "the source argument must stay optional: " + name);
            assertFalse(source.path("description").asText().isBlank(), name);
        }
    }

    private static JsonNode promptByName(JsonNode prompts, String name) {
        for (JsonNode prompt : prompts) {
            if (name.equals(prompt.path("name").asText())) {
                return prompt;
            }
        }
        throw new IllegalStateException("prompt not found in prompts/list: " + name);
    }

    @Test
    void exploreDatabaseRendersForEveryArgumentShape() throws Exception {
        assertGuidance("explore_database", arguments("source", "pg"), "Use source 'pg'");
        assertGuidance("explore_database", arguments("source", "  "), "Available sources: pg, my");
        assertGuidance("explore_database", arguments("source", "nope"), "Source 'nope' is not configured");
        assertGuidance("explore_database", nullArguments(), "Available sources: pg, my");
        assertGuidance("explore_database", MAPPER.createObjectNode(), "Available sources: pg, my");
        assertGuidance("explore_database", null, "Available sources: pg, my");
    }

    @Test
    void erDiagramRendersForEveryArgumentShape() throws Exception {
        assertGuidance("er_diagram", arguments("source", "my"), "Use source 'my'");
        assertGuidance("er_diagram", arguments("source", ""), "Available sources: pg, my");
        assertGuidance("er_diagram", arguments("source", "nope"), "Source 'nope' is not configured");
        assertGuidance("er_diagram", nullArguments(), "Available sources: pg, my");
        assertGuidance("er_diagram", MAPPER.createObjectNode(), "Available sources: pg, my");
        assertGuidance("er_diagram", null, "Available sources: pg, my");
    }

    /** {@code {"source": value}} — the shape clients send for a filled argument. */
    private static ObjectNode arguments(String name, String value) {
        return MAPPER.createObjectNode().put(name, value);
    }

    /** {@code {"source": null}} — the shape clients send for an unset optional argument. */
    private static ObjectNode nullArguments() {
        return MAPPER.createObjectNode().putNull("source");
    }

    /**
     * Renders the prompt and asserts the guidance line the model receives;
     * {@code arguments == null} omits the {@code arguments} key entirely.
     */
    private static void assertGuidance(String prompt, JsonNode arguments, String expectedGuidance) throws Exception {
        ObjectNode params = MAPPER.createObjectNode().put("name", prompt);
        if (arguments != null) {
            params.set("arguments", arguments);
        }
        JsonNode response = client.rpc("prompts/get", params);
        assertFalse(response.has("error"), prompt + " " + params + " failed: " + response);

        JsonNode message = response.path("result").path("messages").get(0);
        assertEquals("user", message.path("role").asText(), response.toString());
        String text = message.path("content").path("text").asText();
        assertTrue(text.contains(expectedGuidance), prompt + " " + params + " -> " + text);
    }
}
