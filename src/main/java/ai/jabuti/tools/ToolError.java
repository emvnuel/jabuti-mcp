package ai.jabuti.tools;

/**
 * An expected tool failure: the client-facing message plus the envelope
 * code the client receives. Anything else that escapes a handler is
 * unexpected and gets the tool's default failure code.
 */
public final class ToolError extends RuntimeException {

    private final String code;

    public ToolError(String message, String code) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
