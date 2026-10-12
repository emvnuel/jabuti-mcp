package ai.jabuti.tools;

/**
 * An expected tool failure: the client-facing message plus the envelope
 * code the client receives. An optional {@code hint} may carry the exact
 * next call a weak LLM client should try; it is nullable and only
 * serialized when non-blank. Anything else that escapes a handler is
 * unexpected and gets the tool's default failure code.
 */
public final class ToolError extends RuntimeException {

    private final String code;
    private final String hint;

    public ToolError(String message, String code) {
        super(message);
        this.code = code;
        this.hint = null;
    }

    public ToolError(String message, String code, String hint) {
        super(message);
        this.code = code;
        this.hint = hint;
    }

    public String code() {
        return code;
    }

    /**
     * The optional machine-readable next-step hint, or {@code null} when absent.
     */
    public String hint() {
        return hint;
    }
}
