package ai.jabuti.tools;

import java.util.Map;
import java.util.function.Supplier;

import io.quarkiverse.mcp.server.ToolResponse;

/**
 * The seam between the tool handlers and the wire: handlers produce data or
 * throw a {@link ToolError}; this module maps the outcome to a
 * {@link ToolEnvelope} inside an MCP {@link ToolResponse}. A failed envelope
 * is returned through {@link ToolResponse#error(String)} so clients also see
 * {@code isError: true}, not just the envelope's {@code success: false}.
 */
public final class ToolCalls {

    private ToolCalls() {
    }

    public static ToolResponse response(String defaultFailureCode, Supplier<Map<String, Object>> work) {
        try {
            return ToolResponse.success(ToolEnvelope.success(work.get()));
        } catch (ToolError e) {
            return ToolResponse.error(ToolEnvelope.error(e.getMessage(), e.code()));
        } catch (RuntimeException e) {
            return ToolResponse.error(ToolEnvelope.error(e, defaultFailureCode));
        }
    }
}
