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
    }
}
