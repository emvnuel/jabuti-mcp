package ai.jabuti.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ToolEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successWrapsData() throws Exception {
        JsonNode json = mapper.readTree(ToolEnvelope.success(Map.of("statements", java.util.List.of())));

        assertTrue(json.get("success").asBoolean());
        assertTrue(json.get("data").has("statements"));
        assertFalse(json.has("error"));
    }

    @Test
    void errorCarriesMessageAndCode() throws Exception {
        JsonNode json = mapper.readTree(ToolEnvelope.error("Read-only mode is enabled", "READONLY_VIOLATION"));

        assertFalse(json.get("success").asBoolean());
        assertEquals("Read-only mode is enabled", json.get("error").asText());
        assertEquals("READONLY_VIOLATION", json.get("code").asText());
        assertFalse(json.has("data"));
        assertFalse(json.has("hint"));
    }

    @Test
    void errorWithHintIncludesHint() throws Exception {
        JsonNode json = mapper.readTree(
                ToolEnvelope.error("Unknown schema", "SCHEMA_NOT_FOUND", "Call search_objects first"));

        assertFalse(json.get("success").asBoolean());
        assertEquals("Unknown schema", json.get("error").asText());
        assertEquals("SCHEMA_NOT_FOUND", json.get("code").asText());
        assertEquals("Call search_objects first", json.get("hint").asText());
    }

    @Test
    void errorWithBlankHintOmitsHintKey() throws Exception {
        assertFalse(mapper.readTree(ToolEnvelope.error("m", "INVALID_INPUT", null)).has("hint"));
        assertFalse(mapper.readTree(ToolEnvelope.error("m", "INVALID_INPUT", "  ")).has("hint"));
    }
}
