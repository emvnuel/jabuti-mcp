package ai.jabuti.driver;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import ai.jabuti.config.JdbcMcpConfigProvider;
import ai.jabuti.config.MavenCoord;
import ai.jabuti.config.RepositoryEntry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

/**
 * Resolves JDBC drivers from the configured Maven repositories on demand and
 * loads them in an isolated {@link URLClassLoader} (a simplified DBeaver
 * {@code DriverUtils} + {@code DriverClassLoader}, without OSGi). With no
 * configured repositories Maven Central is used, exactly as before. Only the
 * configured repositories are consulted: {@code <repositories>} sections
 * inside dependency POMs are ignored.
 *
 * <p>Jars are cached under {@code ~/.jabuti/drivers/<group>/<artifact>/<version>/}
 * (overridable with {@code -Djabuti.drivers-dir=...} for tests); an existing
 * jar is never downloaded again. With {@code -Djabuti.offline=true} only the
 * cache is used and a missing jar fails with a clear message.
 */
@Singleton
public class MavenDriverResolver {

    public static final String CENTRAL_URL = "https://repo.maven.apache.org/maven2/";
    public static final String DRIVERS_DIR_PROPERTY = "jabuti.drivers-dir";
    public static final String OFFLINE_PROPERTY = "jabuti.offline";

    private final List<RepositoryEntry> configuredRepositories;
    private final Map<String, List<Path>> resolvedJars = new ConcurrentHashMap<>();
    private final Map<String, Driver> loadedDrivers = new ConcurrentHashMap<>();

    /**
     * Created on first use, deliberately not at startup: constructing the
     * resolver system during {@code @Startup} widens the window in which the
     * MCP transport can answer {@code tools/list} before tools are
     * registered.
     */
    private RepositorySystem repositorySystem;

    /**
     * Built lazily on first resolution, like {@link #system()}; the configured
     * entries are immutable, so the list never needs invalidation.
     */
    private volatile List<RemoteRepository> remoteRepositories;

    /**
     * Built-in Maven Central only; used by tests and programmatic callers.
     */
    public MavenDriverResolver() {
        this(List.of());
    }

    @Inject
    public MavenDriverResolver(JdbcMcpConfigProvider provider) {
        this(provider.config().repositories());
    }

    /**
     * Uses the given repositories in declaration order; an empty list keeps
     * the implicit Maven Central behavior.
     */
    public MavenDriverResolver(List<RepositoryEntry> repositories) {
        this.configuredRepositories = repositories == null ? List.of() : List.copyOf(repositories);
    }

    /**
     * Downloads (if needed) the driver and its transitive runtime dependencies
     * and instantiates {@code descriptor.className()} inside an isolated
     * class loader.
     */
    public Driver loadDriver(DriverDescriptor descriptor) {
        return loadedDrivers.computeIfAbsent(descriptor.id(), id -> instantiate(descriptor));
    }

    /**
     * Returns the local jar paths for the driver and its transitive runtime
     * dependencies, downloading them from the configured repositories when not
     * cached.
     */
    public List<Path> resolveJars(DriverDescriptor descriptor) {
        String key = descriptor.id() + "@" + descriptor.coordinates();
        return resolvedJars.computeIfAbsent(key, ignored -> resolve(descriptor));
    }

    public Path cacheDir() {
        String configured = System.getProperty(DRIVERS_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".jabuti", "drivers");
    }

    public boolean offline() {
        return Boolean.parseBoolean(System.getProperty(OFFLINE_PROPERTY, "false"));
    }

    private Driver instantiate(DriverDescriptor descriptor) {
        List<Path> jars = resolveJars(descriptor);
        URL[] urls = jars.stream().map(MavenDriverResolver::toUrl).toArray(URL[]::new);
        URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader());
        try {
            Class<?> driverClass = Class.forName(descriptor.className(), true, classLoader);
            Object instance = driverClass.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Driver driver)) {
                throw new IllegalStateException("Class '" + descriptor.className() + "' of driver '"
                        + descriptor.id() + "' does not implement java.sql.Driver");
            }
            return driver;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to instantiate driver class '" + descriptor.className()
                    + "' for driver '" + descriptor.id() + "': " + e.getMessage(), e);
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid jar path " + path + ": " + e.getMessage(), e);
        }
    }

    private List<Path> resolve(DriverDescriptor descriptor) {
        Path cache = prepareCacheDir();
        DefaultRepositorySystemSession session = newSession(cache);
        try {
            return toJars(descriptor, system().resolveDependencies(session, dependencyRequest(descriptor)));
        } catch (Exception e) {
            throw resolutionFailure(descriptor, cache, e);
        }
    }

    private Path prepareCacheDir() {
        Path cache = cacheDir();
        try {
            Files.createDirectories(cache);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create driver cache directory " + cache + ": " + e.getMessage(), e);
        }
        return cache;
    }

    private DefaultRepositorySystemSession newSession(Path cache) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(system().newLocalRepositoryManager(session, new LocalRepository(cache.toFile())));
        session.setOffline(offline());
        // The configured repositories are authoritative: <repositories> sections
        // inside dependency POMs must not add further hosts to resolution.
        session.setIgnoreArtifactDescriptorRepositories(true);
        return session;
    }

    private DependencyRequest dependencyRequest(DriverDescriptor descriptor) {
        CollectRequest collectRequest = new CollectRequest();
        for (MavenCoord coord : descriptor.maven()) {
            Artifact artifact = new DefaultArtifact(coord.groupId(), coord.artifactId(), "jar", coord.version());
            collectRequest.addDependency(new Dependency(artifact, JavaScopes.RUNTIME));
        }
        collectRequest.setRepositories(repositoriesFor(descriptor));
        return new DependencyRequest(collectRequest, DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME));
    }

    /**
     * The repositories to use for a driver: the pinned one when the descriptor
     * names it (validated by the loader), otherwise all configured
     * repositories in declaration order (Maven Central when none are
     * configured).
     */
    private List<RemoteRepository> repositoriesFor(DriverDescriptor descriptor) {
        List<RemoteRepository> repositories = remotes();
        String pinned = descriptor.repository();
        if (pinned == null) {
            return repositories;
        }
        List<RemoteRepository> matching = repositories.stream()
                .filter(repository -> repository.getId().equals(pinned))
                .toList();
        if (matching.isEmpty()) {
            throw new IllegalStateException("Driver '" + descriptor.id() + "' references unknown repository '" + pinned
                    + "'; known repositories: " + repositories.stream().map(RemoteRepository::getId).toList());
        }
        return matching;
    }

    private synchronized List<RemoteRepository> remotes() {
        if (remoteRepositories == null) {
            remoteRepositories = configuredRepositories.isEmpty()
                    ? List.of(central())
                    : configuredRepositories.stream().map(MavenDriverResolver::toRemoteRepository).toList();
        }
        return remoteRepositories;
    }

    private static RemoteRepository toRemoteRepository(RepositoryEntry entry) {
        RemoteRepository.Builder builder = new RemoteRepository.Builder(entry.id(), "default", entry.url());
        if (entry.hasCredentials()) {
            builder.setAuthentication(new AuthenticationBuilder()
                    .addUsername(entry.username())
                    .addPassword(entry.password() != null ? entry.password() : "")
                    .build());
        }
        return builder.build();
    }

    private static List<Path> toJars(DriverDescriptor descriptor, DependencyResult result) throws Exception {
        if (!result.getCollectExceptions().isEmpty()) {
            throw result.getCollectExceptions().get(0);
        }
        List<Path> jars = new ArrayList<>();
        for (ArtifactResult artifactResult : result.getArtifactResults()) {
            Artifact artifact = artifactResult.getArtifact();
            if (artifact == null || artifact.getFile() == null) {
                throw new IllegalStateException("Failed to resolve artifact " + artifactResult.getRequest().getArtifact()
                        + ": " + describe(artifactResult.getExceptions()));
            }
            // A successful artifact result may still carry per-repository
            // exceptions from repositories that did not have the artifact
            // (Aether records them even when a later repository succeeds).
            jars.add(artifact.getFile().toPath());
        }
        List<Path> distinct = jars.stream().distinct().sorted().toList();
        if (distinct.isEmpty()) {
            throw new IllegalStateException("No jars resolved for driver '" + descriptor.id() + "'");
        }
        return distinct;
    }

    private static String describe(List<Exception> exceptions) {
        return exceptions.isEmpty() ? "no file was resolved" : exceptions.get(0).getMessage();
    }

    private IllegalStateException resolutionFailure(DriverDescriptor descriptor, Path cache, Exception cause) {
        String source = repositoriesSource(descriptor);
        if (offline()) {
            return new IllegalStateException("Offline mode is enabled (" + OFFLINE_PROPERTY + "=true) and driver '"
                    + descriptor.id() + "' (" + descriptor.coordinates() + ") is not fully cached in " + cache
                    + ". Disable offline mode to download it from " + source + ".", cause);
        }
        return new IllegalStateException("Unable to download driver '" + descriptor.id() + "' ("
                + descriptor.coordinates() + ") from " + source + ": " + cause.getMessage(), cause);
    }

    /**
     * Human-readable origin for failure messages: the implicit Central default
     * is named explicitly, otherwise the ids actually used for this driver.
     */
    private String repositoriesSource(DriverDescriptor descriptor) {
        if (configuredRepositories.isEmpty()) {
            return "Maven Central";
        }
        String ids = repositoriesFor(descriptor).stream().map(RemoteRepository::getId)
                .collect(Collectors.joining(", "));
        return "the configured repositories (ids: " + ids + ")";
    }

    private synchronized RepositorySystem system() {
        if (repositorySystem == null) {
            repositorySystem = new RepositorySystemSupplier().get();
        }
        return repositorySystem;
    }

    private static RemoteRepository central() {
        return new RemoteRepository.Builder("central", "default", CENTRAL_URL).build();
    }
}
