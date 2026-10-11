package ai.jabuti.config;

import java.util.List;

/**
 * A driver entry as declared in YAML. {@code className} may be empty for
 * built-in ids (the built-in catalog supplies it), and {@code maven} may be
 * empty to inherit the built-in coordinates. {@code repository} optionally
 * pins the id of the repository the driver must be resolved from.
 */
public record DriverEntry(String id, String className, List<MavenCoord> maven, String repository) {

    public DriverEntry {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("driver entry is missing 'id'");
        }
        maven = maven == null ? List.of() : List.copyOf(maven);
        if (repository != null && repository.isBlank()) {
            repository = null;
        }
    }
}
