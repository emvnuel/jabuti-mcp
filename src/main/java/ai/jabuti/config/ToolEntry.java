package ai.jabuti.config;

/**
 * A tool binding between a built-in tool name and a source, as declared in
 * YAML. This record applies its own defaults: {@code readonly=true} and
 * {@code maxRows=100}.
 */
public record ToolEntry(String name, String source, Boolean readonly, Integer maxRows) {

    public ToolEntry {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool entry is missing 'name'");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("tool '" + name + "' is missing 'source'");
        }
        if (readonly == null) {
            readonly = Boolean.TRUE;
        }
        if (maxRows == null) {
            maxRows = DatasourceConfigLoader.DEFAULT_MAX_ROWS;
        } else if (maxRows <= 0) {
            throw new IllegalArgumentException("tool '" + name + "': 'maxRows' must be a positive integer, got: " + maxRows);
        }
    }
}
