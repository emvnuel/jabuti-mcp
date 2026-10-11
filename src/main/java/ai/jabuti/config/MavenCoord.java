package ai.jabuti.config;

/**
 * A pinned Maven coordinate for a JDBC driver artifact.
 */
public record MavenCoord(String groupId, String artifactId, String version) {

    public MavenCoord {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("maven coordinate is missing 'groupId'");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("maven coordinate is missing 'artifactId'");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("maven coordinate is missing 'version'");
        }
    }

    public String coordinates() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
