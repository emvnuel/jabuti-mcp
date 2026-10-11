package ai.jabuti.driver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.jabuti.config.DatasourceConfigLoader;
import ai.jabuti.config.DriverEntry;
import ai.jabuti.config.JdbcMcpConfig;
import ai.jabuti.config.JdbcMcpConfigProvider;
import ai.jabuti.config.MavenCoord;
import ai.jabuti.config.SourceEntry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The driver catalog: built-in entries from {@code builtin-drivers.yaml} merged
 * with {@code drivers:} from the user configuration (YAML wins by id; an empty
 * {@code className} inherits the built-in one). Fails fast when a source
 * references a driver that does not exist, listing the known ids.
 */
@Singleton
public class DriverRegistry {

    public static final String BUILTIN_RESOURCE = "builtin-drivers.yaml";

    private final Map<String, DriverDescriptor> drivers;

    @Inject
    public DriverRegistry(JdbcMcpConfigProvider provider) {
        this(provider.config());
    }

    public DriverRegistry(JdbcMcpConfig config) {
        this.drivers = merge(config);
    }

    public DriverDescriptor require(String driverId) {
        DriverDescriptor descriptor = drivers.get(driverId);
        if (descriptor == null) {
            throw new IllegalStateException("Unknown driver '" + driverId + "'. Known drivers: " + knownIds());
        }
        return descriptor;
    }

    public Set<String> knownIds() {
        return Collections.unmodifiableSet(drivers.keySet());
    }

    private static Map<String, DriverDescriptor> merge(JdbcMcpConfig config) {
        Map<String, DriverDescriptor> drivers = new LinkedHashMap<>();
        for (DriverEntry builtin : DatasourceConfigLoader.loadBuiltinDrivers(BUILTIN_RESOURCE)) {
            drivers.put(builtin.id(), new DriverDescriptor(builtin.id(), builtin.className(), builtin.maven(), null));
        }
        for (DriverEntry entry : config.drivers()) {
            drivers.put(entry.id(), resolveOverride(entry, drivers.get(entry.id())));
        }
        for (SourceEntry source : config.sources()) {
            if (!drivers.containsKey(source.driver())) {
                throw new IllegalStateException("Source '" + source.id() + "' references unknown driver '"
                        + source.driver() + "'. Known drivers: " + drivers.keySet());
            }
        }
        return drivers;
    }

    /**
     * YAML wins over the built-in entry; a blank field inherits from it. A
     * driver that is neither built-in nor fully defined fails fast.
     */
    private static DriverDescriptor resolveOverride(DriverEntry entry, DriverDescriptor builtin) {
        return new DriverDescriptor(entry.id(), inheritClassName(entry, builtin), inheritMaven(entry, builtin),
                entry.repository());
    }

    private static String inheritClassName(DriverEntry entry, DriverDescriptor builtin) {
        if (hasText(entry.className())) {
            return entry.className();
        }
        if (builtin != null && hasText(builtin.className())) {
            return builtin.className();
        }
        throw new IllegalStateException("Driver '" + entry.id()
                + "' must define 'className' (it is not a built-in driver)");
    }

    private static List<MavenCoord> inheritMaven(DriverEntry entry, DriverDescriptor builtin) {
        if (!entry.maven().isEmpty()) {
            return entry.maven();
        }
        if (builtin != null && !builtin.maven().isEmpty()) {
            return builtin.maven();
        }
        throw new IllegalStateException("Driver '" + entry.id()
                + "' must define at least one 'maven' coordinate (it is not a built-in driver)");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
