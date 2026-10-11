package ai.jabuti.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasourceConfigLoaderTest {

    @TempDir
    Path tempDir;

    private JdbcMcpConfig load(String yaml) throws Exception {
        Path file = tempDir.resolve("datasources.yaml");
        Files.writeString(file, yaml);
        return new DatasourceConfigLoader().load(file);
    }

    @Test
    void appliesDefaults() throws Exception {
        JdbcMcpConfig config = load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/app
                    username: app
                    password: secret
                    driver: postgres-jdbc
                tools:
                  - name: execute_sql
                    source: pg
                """);

        SourceEntry source = config.requireSource("pg");
        assertEquals(DatasourceConfigLoader.DEFAULT_POOL_MAX_CONNECTIONS, source.poolMaxConnections());

        ToolEntry tool = config.tools().get(0);
        assertEquals(Boolean.TRUE, tool.readonly());
        assertEquals(DatasourceConfigLoader.DEFAULT_MAX_ROWS, tool.maxRows());
    }

    @Test
    void interpolatesEnvVarsAndKeepsUnknownLiterals() {
        String home = System.getenv("HOME");
        assertEquals(home + "/db", DatasourceConfigLoader.interpolate("${HOME}/db"));
        assertEquals("${JABUTI_TEST_UNSET_VAR}", DatasourceConfigLoader.interpolate("${JABUTI_TEST_UNSET_VAR}"));
        assertEquals("plain", DatasourceConfigLoader.interpolate("plain"));
    }

    @Test
    void interpolatesPasswordsInConfig() throws Exception {
        String home = System.getenv("HOME");
        JdbcMcpConfig config = load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/app
                    username: app
                    password: ${HOME}
                    driver: postgres-jdbc
                """);
        assertEquals(home, config.requireSource("pg").password());
    }

    @Test
    void rejectsDuplicateSourceId() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/b
                    driver: postgres-jdbc
                """));
        assertTrue(error.getMessage().contains("Duplicate source id 'pg'"), error.getMessage());
    }

    @Test
    void rejectsUnknownToolName() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                tools:
                  - name: drop_everything
                    source: pg
                """));
        assertTrue(error.getMessage().contains("Unknown tool 'drop_everything'"), error.getMessage());
    }

    @Test
    void rejectsReadonlyOnSource() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                    readonly: true
                """));
        assertTrue(error.getMessage().contains("tool-level setting"), error.getMessage());
    }

    @Test
    void rejectsToolReferencingUnknownSource() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                tools:
                  - name: execute_sql
                    source: ghost
                """));
        assertTrue(error.getMessage().contains("unknown source 'ghost'"), error.getMessage());
    }

    @Test
    void rejectsNonPositiveToolAndPoolValues() {
        IllegalArgumentException toolError = assertThrows(IllegalArgumentException.class, () -> load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                tools:
                  - { name: execute_sql, source: pg, maxRows: 0 }
                """));
        assertTrue(toolError.getMessage().contains("maxRows"), toolError.getMessage());

        IllegalArgumentException poolError = assertThrows(IllegalArgumentException.class, () -> load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                    poolMaxConnections: -1
                """));
        assertTrue(poolError.getMessage().contains("poolMaxConnections"), poolError.getMessage());
    }

    @Test
    void rejectsFractionalIntegerValues() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                    poolMaxConnections: 2.5
                """));
        assertTrue(error.getMessage().contains("must be an integer"), error.getMessage());
    }

    @Test
    void rejectsMissingFile() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DatasourceConfigLoader().load(tempDir.resolve("nope.yaml")));
        assertTrue(error.getMessage().contains("Configuration file not found"), error.getMessage());
    }

    @Test
    void rejectsUnknownKeys() {
        IllegalStateException topLevel = assertThrows(IllegalStateException.class, () -> load("""
                sourcez: []
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(topLevel.getMessage().contains("Unknown key 'sourcez'"), topLevel.getMessage());

        IllegalStateException source = assertThrows(IllegalStateException.class, () -> load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    usernme: app
                    driver: postgres-jdbc
                """));
        assertTrue(source.getMessage().contains("Unknown key 'usernme'"), source.getMessage());

        IllegalStateException tool = assertThrows(IllegalStateException.class, () -> load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                tools:
                  - name: execute_sql
                    source: pg
                    maxrows: 50
                """));
        assertTrue(tool.getMessage().contains("Unknown key 'maxrows'"), tool.getMessage());
    }

    @Test
    void parsesDriversAndToolOverrides() throws Exception {
        JdbcMcpConfig config = load("""
                sources:
                  - id: exotic
                    url: jdbc:exotic://host/db
                    driver: exotic-jdbc
                    poolMaxConnections: 3
                repositories:
                  - id: internal
                    url: https://repo.example/maven/
                drivers:
                  - id: exotic-jdbc
                    className: com.exotic.Driver
                    repository: internal
                    maven: [{ groupId: com.exotic, artifactId: driver, version: "1.2.3" }]
                tools:
                  - name: execute_sql
                    source: exotic
                    readonly: false
                    maxRows: 42
                """);
        DriverEntry driver = config.drivers().get(0);
        assertEquals("com.exotic.Driver", driver.className());
        assertEquals(new MavenCoord("com.exotic", "driver", "1.2.3"), driver.maven().get(0));
        assertEquals("internal", driver.repository());
        assertEquals(3, config.requireSource("exotic").poolMaxConnections());
        assertEquals(Boolean.FALSE, config.tools().get(0).readonly());
        assertEquals(42, config.tools().get(0).maxRows());
    }

    @Test
    void parsesRepositoriesWithAndWithoutCredentials() throws Exception {
        JdbcMcpConfig config = load("""
                repositories:
                  - id: internal
                    url: https://artifactory.corp.example/artifactory/maven-virtual/
                    username: maven-user
                    password: maven-pass
                  - url: https://repo.maven.apache.org/maven2/
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/app
                    driver: postgres-jdbc
                """);

        assertEquals(2, config.repositories().size());
        RepositoryEntry credentialed = config.repositories().get(0);
        assertEquals(new RepositoryEntry("internal", "https://artifactory.corp.example/artifactory/maven-virtual/",
                "maven-user", "maven-pass"), credentialed);
        assertTrue(credentialed.hasCredentials());

        RepositoryEntry anonymous = config.repositories().get(1);
        assertEquals("central", anonymous.id());
        assertFalse(anonymous.hasCredentials());
    }

    @Test
    void interpolatesEnvVarsInRepositories() throws Exception {
        String home = System.getenv("HOME");
        JdbcMcpConfig config = load("""
                repositories:
                  - id: ${HOME}
                    url: https://repo.example/${HOME}/
                    username: ${HOME}
                    password: ${HOME}
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/app
                    driver: postgres-jdbc
                """);

        RepositoryEntry repository = config.repositories().get(0);
        assertEquals(home, repository.id());
        assertEquals("https://repo.example/" + home + "/", repository.url());
        assertEquals(home, repository.username());
        assertEquals(home, repository.password());
    }

    @Test
    void acceptsDriverPinnedToImplicitCentral() throws Exception {
        JdbcMcpConfig config = load("""
                sources:
                  - id: exotic
                    url: jdbc:exotic://host/db
                    driver: exotic-jdbc
                drivers:
                  - id: exotic-jdbc
                    className: com.exotic.Driver
                    repository: central
                    maven: [{ groupId: com.exotic, artifactId: driver, version: "1.2.3" }]
                """);

        assertEquals("central", config.drivers().get(0).repository());
    }

    @Test
    void absentRepositoriesSectionLeavesConfigEmpty() throws Exception {
        JdbcMcpConfig config = load("""
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/app
                    driver: postgres-jdbc
                """);

        assertTrue(config.repositories().isEmpty());
    }

    @Test
    void rejectsEmptyRepositoriesSection() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> load("""
                repositories: []
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(error.getMessage().contains("at least one repository"), error.getMessage());
    }

    @Test
    void rejectsDuplicateRepositoryIds() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> load("""
                repositories:
                  - { id: internal, url: https://repo1.example/maven/ }
                  - { id: internal, url: https://repo2.example/maven/ }
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(error.getMessage().contains("Duplicate repository id 'internal'"), error.getMessage());
    }

    @Test
    void rejectsUnknownRepositoryKeys() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> load("""
                repositories:
                  - id: internal
                    url: https://repo.example/maven/
                    token: abc
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(error.getMessage().contains("Unknown key 'token'"), error.getMessage());
    }

    @Test
    void rejectsInvalidRepositoryUrls() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> load("""
                repositories:
                  - id: internal
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(missing.getMessage().contains("missing 'url'"), missing.getMessage());

        IllegalArgumentException relative = assertThrows(IllegalArgumentException.class, () -> load("""
                repositories:
                  - { id: internal, url: repo.example/maven }
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(relative.getMessage().contains("relative 'url'"), relative.getMessage());

        IllegalArgumentException scheme = assertThrows(IllegalArgumentException.class, () -> load("""
                repositories:
                  - { id: internal, url: ftp://repo.example/maven }
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(scheme.getMessage().contains("unsupported 'url' scheme 'ftp'"), scheme.getMessage());

        IllegalArgumentException userinfo = assertThrows(IllegalArgumentException.class, () -> load("""
                repositories:
                  - { id: internal, url: "https://user:secret@repo.example/maven/" }
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(userinfo.getMessage().contains("credentials in 'url'"), userinfo.getMessage());
        assertFalse(userinfo.getMessage().contains("secret"), userinfo.getMessage());
    }

    @Test
    void neverEchoesCredentialsFromInvalidRepositoryUrls() {
        // A rejected scheme must not print the raw URL (and its credentials).
        IllegalArgumentException scheme = assertThrows(IllegalArgumentException.class, () -> load("""
                repositories:
                  - { id: internal, url: ftp://user:secret@repo.example/maven/ }
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(scheme.getMessage().contains("unsupported 'url' scheme 'ftp'"), scheme.getMessage());
        assertFalse(scheme.getMessage().contains("secret"), scheme.getMessage());

        // A parse failure embeds the raw URL in URISyntaxException messages.
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class, () -> load("""
                repositories:
                  - { id: internal, url: "https://user:secret@repo.example/maven/${UNSET_VAR}/" }
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(broken.getMessage().contains("invalid 'url'"), broken.getMessage());
        assertTrue(broken.getMessage().contains("unresolved ${VAR}"), broken.getMessage());
        assertFalse(broken.getMessage().contains("secret"), broken.getMessage());
    }

    @Test
    void rejectsRepositoryUrlsWithUnparseableAuthority() {
        // An unencoded '@' inside the password escapes getUserInfo() but makes
        // the authority unusable for the HTTP transport.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load("""
                repositories:
                  - { id: internal, url: "https://user:p@ss@repo.example/maven/" }
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(error.getMessage().contains("invalid 'url' (not a valid host)"), error.getMessage());
        assertFalse(error.getMessage().contains("p@ss"), error.getMessage());
    }

    @Test
    void rejectsPasswordWithoutUsername() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load("""
                repositories:
                  - id: internal
                    url: https://repo.example/maven/
                    password: maven-pass
                sources:
                  - id: pg
                    url: jdbc:postgresql://localhost:5432/a
                    driver: postgres-jdbc
                """));
        assertTrue(error.getMessage().contains("'password' without 'username'"), error.getMessage());
    }

    @Test
    void rejectsBlankDriverRepository() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> load("""
                sources:
                  - id: exotic
                    url: jdbc:exotic://host/db
                    driver: exotic-jdbc
                drivers:
                  - id: exotic-jdbc
                    className: com.exotic.Driver
                    repository: ""
                    maven: [{ groupId: com.exotic, artifactId: driver, version: "1.2.3" }]
                """));
        assertTrue(error.getMessage().contains("'repository' must not be blank"), error.getMessage());
    }

    @Test
    void rejectsDriverReferencingUnknownRepository() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> load("""
                sources:
                  - id: exotic
                    url: jdbc:exotic://host/db
                    driver: exotic-jdbc
                repositories:
                  - { id: internal, url: https://repo.example/maven/ }
                drivers:
                  - id: exotic-jdbc
                    className: com.exotic.Driver
                    repository: nuclear
                    maven: [{ groupId: com.exotic, artifactId: driver, version: "1.2.3" }]
                """));
        assertTrue(error.getMessage().contains("references unknown repository 'nuclear'"), error.getMessage());
        assertTrue(error.getMessage().contains("internal"), error.getMessage());
    }
}
