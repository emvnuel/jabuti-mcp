package ai.jabuti.driver;

import java.util.List;

import ai.jabuti.config.MavenCoord;

/**
 * A resolved driver: always has a class name and at least one pinned Maven
 * coordinate (built-in catalog entry or YAML override). {@code repository} is
 * the id of the repository the driver must be resolved from, or {@code null}
 * to use all configured repositories.
 */
public record DriverDescriptor(String id, String className, List<MavenCoord> maven, String repository) {

    public DriverDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("driver descriptor is missing 'id'");
        }
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("driver '" + id + "' is missing 'className'");
        }
        if (maven == null || maven.isEmpty()) {
            throw new IllegalArgumentException("driver '" + id + "' has no Maven coordinates");
        }
        maven = List.copyOf(maven);
    }

    public String coordinates() {
        return maven.stream().map(MavenCoord::coordinates).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
