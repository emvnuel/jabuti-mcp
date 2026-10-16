package ai.jabuti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    private static Process server;
    private static BufferedWriter serverIn;
    private static BufferedReader serverOut;
    private static Path tempDir;
    private static Path stderrFile;
    private static int requestId;

    @BeforeAll
    static void startServer() throws Exception {
        tempDir = Files.createTempDirectory("jabuti-mcp-prompt-it");
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
        stderrFile = tempDir.resolve("server-stderr.log");

        Path runner = Path.of("target/quarkus-app/quarkus-run.jar").toAbsolutePath();
        assertTrue(Files.exists(runner),
                "packaged runner not found at " + runner + " (mvn verify packages before running ITs)");

        ProcessBuilder processBuilder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djabuti.config=" + configFile,
                "-Djabuti.drivers-dir=" + tempDir.resolve("drivers"),
                "-jar", runner.toString());
        processBuilder.redirectError(stderrFile.toFile());
        server = processBuilder.start();
        serverIn = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
        serverOut = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

        JsonNode init = rpc("initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "prompt-args-it", "version", "1.0")));
        assertEquals("2025-06-18", init.path("result").path("protocolVersion").asText(),
                "initialize failed: " + init);
        notify("notifications/initialized", Map.of());
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (serverIn != null) {
            serverIn.close();
        }
        if (server != null) {
            if (!server.waitFor(30, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        }
    }

    @Test
    void listsBothPromptsWithAnOptionalSourceArgument() throws Exception {
        JsonNode prompts = rpc("prompts/list", Map.of()).path("result").path("prompts");
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
        JsonNode response = rpc("prompts/get", params);
        assertFalse(response.has("error"), prompt + " " + params + " failed: " + response);

        JsonNode message = response.path("result").path("messages").get(0);
        assertEquals("user", message.path("role").asText(), response.toString());
        String text = message.path("content").path("text").asText();
        assertTrue(text.contains(expectedGuidance), prompt + " " + params + " -> " + text);
    }

    private static JsonNode rpc(String method, Object params) throws Exception {
        int id = ++requestId;
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        serverIn.write(MAPPER.writeValueAsString(message));
        serverIn.newLine();
        serverIn.flush();

        while (true) {
            String line = serverOut.readLine();
            if (line == null) {
                throw new IllegalStateException("server exited before responding to " + method
                        + "; stderr:\n" + Files.readString(stderrFile));
            }
            JsonNode node = MAPPER.readTree(line);
            if (node.has("id") && node.get("id").asInt() == id) {
                return node;
            }
        }
    }

    private static void notify(String method, Object params) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        serverIn.write(MAPPER.writeValueAsString(message));
        serverIn.newLine();
        serverIn.flush();
    }
}
