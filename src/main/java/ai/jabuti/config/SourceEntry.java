package ai.jabuti.config;

/**
 * A database source as declared in YAML. {@code poolMaxConnections} is
 * optional and defaults to {@link DatasourceConfigLoader#DEFAULT_POOL_MAX_CONNECTIONS}.
 * Per-tool knobs ({@code readonly}, {@code maxRows}) are intentionally absent
 * here: they belong to {@link ToolEntry}.
 */
public record SourceEntry(String id, String url, String username, String password,
        String driver, Integer poolMaxConnections) {

    public SourceEntry {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("source entry is missing 'id'");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("source '" + id + "' is missing 'url'");
        }
        if (driver == null || driver.isBlank()) {
            throw new IllegalArgumentException("source '" + id + "' is missing 'driver'");
        }
        if (poolMaxConnections == null) {
            poolMaxConnections = DatasourceConfigLoader.DEFAULT_POOL_MAX_CONNECTIONS;
        } else if (poolMaxConnections <= 0) {
            throw new IllegalArgumentException("source '" + id + "': 'poolMaxConnections' must be a positive integer, got: "
                    + poolMaxConnections);
        }
    }
}
