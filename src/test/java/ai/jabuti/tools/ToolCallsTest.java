package ai.jabuti.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.Test;

/**
 * The outcome-to-envelope mapping: success data, expected tool errors with
 * their own code, and unexpected failures with the tool's default code. A
 * failed envelope also sets the MCP {@code isError} flag so clients see the
 * failure, not just the model.
 */
class ToolCallsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successWrapsDataAndClearsIsError() throws Exception {
        ToolResponse response = ToolCalls.response(ToolEnvelope.CODE_EXECUTION_ERROR, () -> Map.of("k", "v"));

        assertFalse(response.isError());
        JsonNode json = mapper.readTree(text(response));
        assertTrue(json.get("success").asBoolean());
        assertEquals("v", json.get("data").get("k").asText());
    }

    @Test
    void toolErrorKeepsItsCodeAndSetsIsError() throws Exception {
        ToolResponse response = ToolCalls.response(ToolEnvelope.CODE_EXECUTION_ERROR, () -> {
            throw new ToolError("nope", ToolEnvelope.CODE_READONLY_VIOLATION);
        });

        assertTrue(response.isError());
        JsonNode json = mapper.readTree(text(response));
        assertFalse(json.get("success").asBoolean());
        assertEquals("nope", json.get("error").asText());
        assertEquals(ToolEnvelope.CODE_READONLY_VIOLATION, json.get("code").asText());
    }

    @Test
    void unexpectedFailureGetsTheDefaultCodeAndSetsIsError() throws Exception {
        ToolResponse response = ToolCalls.response(ToolEnvelope.CODE_SEARCH_ERROR, () -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(response.isError());
        JsonNode json = mapper.readTree(text(response));
        assertFalse(json.get("success").asBoolean());
        assertEquals("boom", json.get("error").asText());
        assertEquals(ToolEnvelope.CODE_SEARCH_ERROR, json.get("code").asText());
    }

    private static String text(ToolResponse response) {
        return ((TextContent) response.content().get(0)).text();
    }
}
