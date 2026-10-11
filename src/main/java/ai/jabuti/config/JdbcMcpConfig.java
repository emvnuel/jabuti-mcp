package ai.jabuti.config;

import java.util.List;

/**
 * The fully parsed and validated contents of {@code datasources.yaml}. An
 * empty {@code repositories} list means no {@code repositories:} section was
 * declared: the driver resolver then defaults to Maven Central.
 */
public record JdbcMcpConfig(List<SourceEntry> sources, List<DriverEntry> drivers, List<ToolEntry> tools,
        List<RepositoryEntry> repositories) {

    public JdbcMcpConfig {
        sources = sources == null ? List.of() : List.copyOf(sources);
        drivers = drivers == null ? List.of() : List.copyOf(drivers);
        tools = tools == null ? List.of() : List.copyOf(tools);
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }

    public SourceEntry requireSource(String id) {
        return sources.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException(
                "Unknown source '" + id + "'. Known sources: " + sources.stream().map(SourceEntry::id).toList()));
    }
}
