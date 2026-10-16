package ai.jabuti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Drives the packaged server over stdio for the integration tests: boots
 * {@code target/quarkus-app/quarkus-run.jar} with the given YAML config and a
 * drivers directory that never touches the real {@code ~/.jabuti}, performs
 * the MCP initialize handshake and exchanges JSON-RPC messages.
 */
final class StdioServerHarness implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path RUNNER = Path.of("target/quarkus-app/quarkus-run.jar").toAbsolutePath();

    private final Process server;
    private final BufferedWriter serverIn;
    private final BufferedReader serverOut;
    private final Path stderrFile;
    private int requestId;

    /**
     * Boots the server and initializes the MCP session as {@code clientName}.
     */
    static StdioServerHarness start(Path configFile, Path driversDir, String clientName) throws Exception {
        assertTrue(Files.exists(RUNNER),
                "packaged runner not found at " + RUNNER + " (mvn verify packages before running ITs)");

        Path stderrFile = Files.createTempFile("jabuti-mcp-stderr-", ".log");
        ProcessBuilder processBuilder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djabuti.config=" + configFile,
                "-Djabuti.drivers-dir=" + driversDir,
                "-jar", RUNNER.toString());
        processBuilder.redirectError(stderrFile.toFile());

        StdioServerHarness harness = new StdioServerHarness(processBuilder.start(), stderrFile);
        JsonNode init = harness.rpc("initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", clientName, "version", "1.0")));
        assertEquals("2025-06-18", init.path("result").path("protocolVersion").asText(),
                "initialize failed: " + init);
        harness.notify("notifications/initialized", Map.of());
        return harness;
    }

    private StdioServerHarness(Process server, Path stderrFile) {
        this.server = server;
        this.stderrFile = stderrFile;
        this.serverIn = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
        this.serverOut = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Sends a request and returns the response with the matching id; anything
     * else the server writes in the meantime (notifications, logs) is skipped.
     */
    JsonNode rpc(String method, Object params) throws Exception {
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

    /** Sends a JSON-RPC notification: no id, no response. */
    private void notify(String method, Object params) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        serverIn.write(MAPPER.writeValueAsString(message));
        serverIn.newLine();
        serverIn.flush();
    }

    /** Closes the request stream and waits for the server to exit. */
    @Override
    public void close() throws Exception {
        serverIn.close();
        if (!server.waitFor(30, TimeUnit.SECONDS)) {
            server.destroyForcibly();
        }
    }
}
