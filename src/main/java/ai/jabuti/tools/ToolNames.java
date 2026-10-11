package ai.jabuti.tools;

/**
 * Per-source tool naming (mirrors dbhub's {@code normalizeSourceId} and
 * {@code resolveBuiltinToolNaming}): a single-source deployment keeps the bare
 * tool name; multiple sources suffix it with the normalized source id.
 */
public final class ToolNames {

    private ToolNames() {
    }

    /**
     * Converts all non-alphanumeric characters to underscores.
     * {@code "staging-db"} becomes {@code "staging_db"}.
     */
    public static String normalizeSourceId(String id) {
        return id.replaceAll("[^a-zA-Z0-9]", "_");
    }

    public static String toolName(String base, String sourceId, boolean singleSource) {
        return singleSource ? base : base + "_" + normalizeSourceId(sourceId);
    }
}
