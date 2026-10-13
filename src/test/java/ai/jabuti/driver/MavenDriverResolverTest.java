package ai.jabuti.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import ai.jabuti.config.JdbcMcpConfig;
import ai.jabuti.config.MavenCoord;
import ai.jabuti.config.RepositoryEntry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenDriverResolverTest {

    private static final MavenCoord FIXTURE = new MavenCoord("ai.jabuti.test", "fixture-driver", "1.0.0");
    private static final MavenCoord FIXTURE_CHILD = new MavenCoord("ai.jabuti.test", "fixture-child", "1.0.0");

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty(MavenDriverResolver.DRIVERS_DIR_PROPERTY, tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(MavenDriverResolver.DRIVERS_DIR_PROPERTY);
        System.clearProperty(MavenDriverResolver.OFFLINE_PROPERTY);
    }

    /**
     * The built-in catalog entry, so the resolver test always exercises the
     * pinned coordinate and a version bump (e.g. a Renovate PR) never requires
     * editing this test.
     */
    private static DriverDescriptor postgres() {
        return new DriverRegistry(new JdbcMcpConfig(List.of(), List.of(), List.of(), List.of()))
                .require("postgres-jdbc");
    }

    /**
     * Maven layout of a pinned jar:
     * {@code <group path>/<artifact>/<version>/<artifact>-<version>.jar}.
     */
    private static String pinnedJarPath(MavenCoord coordinate) {
        return "%s/%s/%s/%s-%s.jar".formatted(
                coordinate.groupId().replace('.', '/'), coordinate.artifactId(), coordinate.version(),
                coordinate.artifactId(), coordinate.version());
    }

    private static DriverDescriptor fixtureDriver(String repository) {
        return new DriverDescriptor("fixture-jdbc", "com.example.FixtureDriver", List.of(FIXTURE), repository);
    }

    private static Path fixtureJar(List<Path> jars) {
        return jars.stream()
                .filter(path -> path.getFileName().toString().equals("fixture-driver-1.0.0.jar"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fixture jar not resolved, got: " + jars));
    }

    private static String tracking(Path jar) throws IOException {
        return Files.readString(jar.getParent().resolve("_remote.repositories"));
    }

    @Test
    void downloadsAndLoadsDriverInIsolatedClassLoader() throws Exception {
        MavenDriverResolver resolver = new MavenDriverResolver();
        DriverDescriptor descriptor = postgres();

        List<Path> jars = resolver.resolveJars(descriptor);

        assertFalse(jars.isEmpty());
        assertTrue(jars.stream().allMatch(Files::exists));
        assertTrue(jars.stream().anyMatch(
                path -> path.toString().replace('\\', '/')
                        .endsWith(pinnedJarPath(descriptor.maven().get(0)))),
                "expected the pinned jar in <group>/<artifact>/<version>/, got: " + jars);

        Driver driver = resolver.loadDriver(descriptor);
        assertEquals("org.postgresql.Driver", driver.getClass().getName());
        assertNotSame(MavenDriverResolver.class.getClassLoader(), driver.getClass().getClassLoader(),
                "driver must come from its own URLClassLoader, not the application classpath");
        assertTrue(driver.acceptsURL("jdbc:postgresql://localhost:5432/app"));
    }

    @Test
    void reusesCachedJarWhenOffline() {
        MavenDriverResolver online = new MavenDriverResolver();
        List<Path> firstResolution = online.resolveJars(postgres());

        System.setProperty(MavenDriverResolver.OFFLINE_PROPERTY, "true");
        MavenDriverResolver offline = new MavenDriverResolver();
        List<Path> cachedResolution = offline.resolveJars(postgres());

        assertEquals(firstResolution, cachedResolution);
        assertEquals("org.postgresql.Driver", offline.loadDriver(postgres()).getClass().getName());
    }

    @Test
    void offlineWithoutCacheFailsWithClearMessage() {
        System.setProperty(MavenDriverResolver.OFFLINE_PROPERTY, "true");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new MavenDriverResolver().resolveJars(postgres()));

        assertTrue(error.getMessage().contains("Offline mode"), error.getMessage());
        assertTrue(error.getMessage().contains("postgres-jdbc"), error.getMessage());
        assertTrue(error.getMessage().contains("Maven Central"), error.getMessage());
    }

    @Test
    void resolvesFromConfiguredRepositoryInsteadOfCentral() throws Exception {
        try (FakeRepository repository = new FakeRepository("internal")) {
            repository.publish(FIXTURE);
            MavenDriverResolver resolver = new MavenDriverResolver(
                    List.of(new RepositoryEntry("internal", repository.url(), null, null)));

            List<Path> jars = resolver.resolveJars(fixtureDriver(null));

            Path jar = fixtureJar(jars);
            assertTrue(Files.exists(jar));
            assertTrue(tracking(jar).contains("fixture-driver-1.0.0.jar>internal="), tracking(jar));
            assertTrue(repository.requestedPaths().stream().anyMatch(path -> path.endsWith("fixture-driver-1.0.0.jar")),
                    "expected the jar request on the configured repository, got: " + repository.requestedPaths());
        }
    }

    @Test
    void sendsBasicAuthWhenCredentialsAreConfigured() throws Exception {
        try (FakeRepository repository = new FakeRepository("internal")) {
            repository.requireBasicAuth("maven-user", "maven-pass");
            repository.publish(FIXTURE);
            MavenDriverResolver resolver = new MavenDriverResolver(
                    List.of(new RepositoryEntry("internal", repository.url(), "maven-user", "maven-pass")));

            List<Path> jars = resolver.resolveJars(fixtureDriver(null));

            assertTrue(Files.exists(fixtureJar(jars)));
        }
    }

    @Test
    void perDriverRepositoryRestrictsResolution() throws Exception {
        try (FakeRepository first = new FakeRepository("first");
                FakeRepository second = new FakeRepository("second")) {
            second.publish(FIXTURE);
            MavenDriverResolver resolver = new MavenDriverResolver(List.of(
                    new RepositoryEntry("first", first.url(), null, null),
                    new RepositoryEntry("second", second.url(), null, null)));

            List<Path> jars = resolver.resolveJars(fixtureDriver("second"));

            assertTrue(Files.exists(fixtureJar(jars)));
            assertTrue(first.requestedPaths().isEmpty(),
                    "a pinned driver must not contact other repositories: " + first.requestedPaths());
            assertFalse(second.requestedPaths().isEmpty());
        }
    }

    @Test
    void ignoresRepositoriesDeclaredInDependencyPoms() throws Exception {
        try (FakeRepository primary = new FakeRepository("primary");
                FakeRepository extra = new FakeRepository("extra")) {
            extra.publish(FIXTURE_CHILD);
            primary.publish(FIXTURE, """
                    <dependencies>
                      <dependency>
                        <groupId>%s</groupId>
                        <artifactId>%s</artifactId>
                        <version>%s</version>
                      </dependency>
                    </dependencies>
                    <repositories>
                      <repository>
                        <id>extra</id>
                        <url>%s</url>
                      </repository>
                    </repositories>
                    """.formatted(FIXTURE_CHILD.groupId(), FIXTURE_CHILD.artifactId(), FIXTURE_CHILD.version(),
                    extra.url()));
            MavenDriverResolver resolver = new MavenDriverResolver(
                    List.of(new RepositoryEntry("primary", primary.url(), null, null)));

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> resolver.resolveJars(fixtureDriver(null)));

            assertTrue(error.getMessage().contains("fixture-child"), error.getMessage());
            assertTrue(extra.requestedPaths().isEmpty(),
                    "repositories declared inside dependency POMs must be ignored: " + extra.requestedPaths());
        }
    }

    @Test
    void configuredRepoOrderIsRespected() throws Exception {
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        try (FakeRepository first = new FakeRepository("first", requests);
                FakeRepository second = new FakeRepository("second", requests)) {
            second.publish(FIXTURE);
            MavenDriverResolver resolver = new MavenDriverResolver(List.of(
                    new RepositoryEntry("first", first.url(), null, null),
                    new RepositoryEntry("second", second.url(), null, null)));

            List<Path> jars = resolver.resolveJars(fixtureDriver(null));

            Path jar = fixtureJar(jars);
            assertTrue(tracking(jar).contains("fixture-driver-1.0.0.jar>second="), tracking(jar));
            String jarPath = "/ai/jabuti/test/fixture-driver/1.0.0/fixture-driver-1.0.0.jar";
            int firstJarRequest = firstRequestIndex(requests, "first", jarPath);
            int secondJarRequest = firstRequestIndex(requests, "second", jarPath);
            assertTrue(firstJarRequest >= 0, "the first repository must be tried first: " + requests);
            assertTrue(secondJarRequest > firstJarRequest,
                    "the second repository must serve the artifact after the first misses: " + requests);
        }
    }

    @Test
    void offlineReusesCacheFromSameRepositoryConfig() throws Exception {
        FakeRepository repository = new FakeRepository("internal");
        repository.publish(FIXTURE);
        List<RepositoryEntry> configuration =
                List.of(new RepositoryEntry("internal", repository.url(), null, null));

        List<Path> online = new MavenDriverResolver(configuration).resolveJars(fixtureDriver(null));
        repository.close();

        System.setProperty(MavenDriverResolver.OFFLINE_PROPERTY, "true");
        List<Path> offline = new MavenDriverResolver(configuration).resolveJars(fixtureDriver(null));

        assertEquals(online, offline);
    }

    @Test
    void differentRepositoryIdDoesNotReuseCache() throws Exception {
        FakeRepository repository = new FakeRepository("internal");
        repository.publish(FIXTURE);
        String url = repository.url();

        List<Path> initial = new MavenDriverResolver(List.of(new RepositoryEntry("internal", url, null, null)))
                .resolveJars(fixtureDriver(null));
        int requestsAfterFirstDownload = repository.requestedPaths().size();

        // Same id: the tracking file matches and the cache is reused as-is.
        List<Path> cached = new MavenDriverResolver(List.of(new RepositoryEntry("internal", url, null, null)))
                .resolveJars(fixtureDriver(null));
        assertEquals(initial, cached);
        assertEquals(requestsAfterFirstDownload, repository.requestedPaths().size(),
                "a matching repository id must reuse the cache");

        // Different id, online: the artifact is downloaded again.
        List<Path> renamed = new MavenDriverResolver(List.of(new RepositoryEntry("renamed", url, null, null)))
                .resolveJars(fixtureDriver(null));
        assertEquals(initial, renamed);
        assertTrue(repository.requestedPaths().size() > requestsAfterFirstDownload,
                "a different repository id must re-download");

        // Different id, offline: no repository can serve it, resolution fails.
        repository.close();
        System.setProperty(MavenDriverResolver.OFFLINE_PROPERTY, "true");
        MavenDriverResolver offline =
                new MavenDriverResolver(List.of(new RepositoryEntry("renamed-again", url, null, null)));

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> offline.resolveJars(fixtureDriver(null)));
        assertTrue(error.getMessage().contains("Offline mode"), error.getMessage());
    }

    @Test
    void failureMessageDoesNotLeakCredentials() throws Exception {
        try (FakeRepository repository = new FakeRepository("internal")) {
            repository.requireBasicAuth("maven-user", "top-secret-password");
            MavenDriverResolver resolver = new MavenDriverResolver(List.of(
                    new RepositoryEntry("internal", repository.url(), "maven-user", "top-secret-password")));

            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> resolver.resolveJars(fixtureDriver(null)));

            assertTrue(error.getMessage().contains("configured repositories"), error.getMessage());
            assertTrue(error.getMessage().contains("internal"), error.getMessage());
            assertFalse(error.getMessage().contains("top-secret-password"), error.getMessage());
            assertFalse(error.getMessage().contains("maven-user"), error.getMessage());
        }
    }

    private static int firstRequestIndex(List<String> requests, String repositoryId, String pathSuffix) {
        for (int i = 0; i < requests.size(); i++) {
            String request = requests.get(i);
            if (request.startsWith(repositoryId + " ") && request.endsWith(pathSuffix)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A minimal Maven repository over the JDK HTTP server: serves fabricated
     * poms and jars for published coordinates, optionally requiring basic auth
     * (challenge or preemptive). Every request is recorded for assertions.
     */
    private static final class FakeRepository implements AutoCloseable {

        private final HttpServer server;
        private final String id;
        private final List<String> requestLog;
        private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        private volatile String requiredAuthorization;

        FakeRepository(String id) throws IOException {
            this(id, new CopyOnWriteArrayList<>());
        }

        FakeRepository(String id, List<String> requestLog) throws IOException {
            this.id = id;
            this.requestLog = requestLog;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        FakeRepository requireBasicAuth(String username, String password) {
            requiredAuthorization = "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            return this;
        }

        void publish(MavenCoord coord) throws IOException {
            publish(coord, "");
        }

        void publish(MavenCoord coord, String extraPomXml) throws IOException {
            String base = coord.groupId().replace('.', '/') + "/" + coord.artifactId() + "/" + coord.version() + "/"
                    + coord.artifactId() + "-" + coord.version();
            files.put(base + ".pom", pom(coord, extraPomXml));
            files.put(base + ".jar", jar());
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        List<String> requestedPaths() {
            return List.copyOf(requestedPaths);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String key = path.startsWith("/") ? path.substring(1) : path;
            requestedPaths.add(path);
            requestLog.add(id + " " + path);
            try {
                String required = requiredAuthorization;
                if (required != null && !required.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"jabuti-mcp-test\"");
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                byte[] content = files.get(key);
                if (content == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            } finally {
                exchange.close();
            }
        }

        private static byte[] pom(MavenCoord coord, String extraXml) {
            return """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                      %s
                    </project>
                    """.formatted(coord.groupId(), coord.artifactId(), coord.version(), extraXml)
                    .getBytes(StandardCharsets.UTF_8);
        }

        private static byte[] jar() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return bytes.toByteArray();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
