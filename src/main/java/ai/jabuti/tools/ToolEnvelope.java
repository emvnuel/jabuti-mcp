package ai.jabuti.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Every tool handler returns one of these JSON envelopes (mirrors dbhub's
 * {@code createToolSuccessResponse}/{@code createToolErrorResponse} payloads):
 * {@code {"success":true,"data":{...}}} or
 * {@code {"success":false,"error":"...","code":"..."}}. Error envelopes may
 * carry an optional machine-readable {@code hint} (the exact next call a
 * weak LLM client should try), serialized only when non-null and non-blank.
 */
public final class ToolEnvelope {

    public static final String CODE_READONLY_VIOLATION = "READONLY_VIOLATION";
    public static final String CODE_INVALID_TABLE_FILTER = "INVALID_TABLE_FILTER";
    public static final String CODE_SCHEMA_NOT_FOUND = "SCHEMA_NOT_FOUND";
    public static final String CODE_INVALID_OBJECT_TYPE = "INVALID_OBJECT_TYPE";
    public static final String CODE_INVALID_INPUT = "INVALID_INPUT";
    public static final String CODE_EXECUTION_ERROR = "EXECUTION_ERROR";
    public static final String CODE_SEARCH_ERROR = "SEARCH_ERROR";
    public static final String CODE_UNSUPPORTED = "UNSUPPORTED";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolEnvelope() {
    }

    /**
     * The throwable's message, falling back to its string representation so an
     * error envelope never carries a {@code null} message.
     */
    public static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    public static String success(Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", true);
        envelope.put("data", data);
        return toJson(envelope);
    }

    public static String error(String message, String code) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", false);
        envelope.put("error", message);
        envelope.put("code", code);
        return toJson(envelope);
    }

    /**
     * Error envelope with an optional machine-readable {@code hint}. The
     * {@code "hint"} key is included only when the hint is non-null and
     * non-blank, placed after {@code "code"}.
     */
    public static String error(String message, String code, String hint) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", false);
        envelope.put("error", message);
        envelope.put("code", code);
        if (hint != null && !hint.isBlank()) {
            envelope.put("hint", hint);
        }
        return toJson(envelope);
    }

    public static String error(Throwable e, String code) {
        return error(message(e), code);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize tool response: " + e.getMessage(), e);
        }
    }
}
