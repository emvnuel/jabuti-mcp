package ai.jabuti.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads and validates {@code datasources.yaml}. Fails fast with actionable
 * messages: unknown keys where they do not belong, duplicate ids, unknown tool
 * names and dangling source references are all rejected before the server
 * starts.
 */
public final class DatasourceConfigLoader {

    public static final String CONFIG_PATH_PROPERTY = "jabuti.config";
    public static final int DEFAULT_MAX_ROWS = 100;
    public static final int DEFAULT_POOL_MAX_CONNECTIONS = 10;
    public static final Set<String> KNOWN_TOOLS = Set.of(
            "execute_sql", "search_objects", "explain_sql", "health_check", "database_info");

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final Set<String> SOURCE_TOOL_KEYS = Set.of("readonly", "maxrows", "max_rows");
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("sources", "drivers", "tools", "repositories");
    private static final Set<String> SOURCE_KEYS =
            Set.of("id", "url", "username", "password", "driver", "poolMaxConnections");
    private static final Set<String> DRIVER_KEYS = Set.of("id", "className", "maven", "repository");
    private static final Set<String> MAVEN_KEYS = Set.of("groupId", "artifactId", "version");
    private static final Set<String> REPOSITORY_KEYS = Set.of("id", "url", "username", "password");
    private static final Set<String> TOOL_KEYS = Set.of("name", "source", "readonly", "maxRows");

    public JdbcMcpConfig load(Path yamlFile) {
        Path path = yamlFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Configuration file not found: " + path
                    + " (set JABUTI_CONFIG or -Djabuti.config to point at your datasources.yaml)");
        }
        Object root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read configuration file " + path + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid YAML in " + path + ": " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("Configuration file " + path + " must contain a YAML mapping with a 'sources' list");
        }
        Map<String, Object> map = stringKeyed(rawMap);
        rejectUnknownKeys(map, TOP_LEVEL_KEYS, "the top level of " + path);

        List<SourceEntry> sources = parseSources(map);
        List<RepositoryEntry> repositories = parseRepositories(map);
        List<DriverEntry> drivers = parseDrivers(map);
        rejectUnknownRepositoryReferences(drivers, repositories);
        List<ToolEntry> tools = parseTools(map, sources);
        return new JdbcMcpConfig(sources, drivers, tools, repositories);
    }

    /**
     * Loads the built-in driver catalog from the classpath, using the same
     * mapping and validation as the {@code drivers:} section of the user
     * configuration.
     */
    public static List<DriverEntry> loadBuiltinDrivers(String resource) {
        try (InputStream in = DatasourceConfigLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Built-in driver catalog " + resource + " not found on the classpath");
            }
            Object root = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> rawMap)) {
                throw new IllegalStateException("Invalid built-in driver catalog " + resource);
            }
            return parseDrivers(stringKeyed(rawMap));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read built-in driver catalog: " + e.getMessage(), e);
        }
    }

    private static List<SourceEntry> parseSources(Map<String, Object> map) {
        Set<String> ids = new HashSet<>();
        List<SourceEntry> sources = mapEntries(map.get("sources"), "'sources'", entry -> {
            rejectToolLevelKeys(entry);
            SourceEntry source = new SourceEntry(
                    string(entry, "id"),
                    string(entry, "url"),
                    string(entry, "username"),
                    string(entry, "password"),
                    string(entry, "driver"),
                    integer(entry, "poolMaxConnections"));
            requireUnique(ids, source.id(), "Duplicate source id '" + source.id() + "' in 'sources'");
            rejectUnknownKeys(entry, SOURCE_KEYS, "source '" + source.id() + "'");
            return source;
        });
        if (sources.isEmpty()) {
            throw new IllegalStateException("Configuration must define at least one entry under 'sources'");
        }
        return sources;
    }

    private static void rejectToolLevelKeys(Map<String, Object> entry) {
        for (String key : entry.keySet()) {
            if (SOURCE_TOOL_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Key '" + key + "' is a tool-level setting and must not appear on a source"
                        + " (source '" + entry.get("id") + "'); move it to the 'tools' section");
            }
        }
    }

    private static List<DriverEntry> parseDrivers(Map<String, Object> map) {
        Set<String> ids = new HashSet<>();
        return mapEntries(map.get("drivers"), "'drivers'", entry -> {
            String id = string(entry, "id");
            String repository = string(entry, "repository");
            if (repository != null && repository.isBlank()) {
                throw new IllegalStateException("Driver '" + id + "': 'repository' must not be blank"
                        + " (remove the key to resolve from all configured repositories)");
            }
            DriverEntry driver = new DriverEntry(
                    id,
                    string(entry, "className"),
                    parseMavenCoords(entry.get("maven"), id),
                    repository);
            requireUnique(ids, driver.id(), "Duplicate driver id '" + driver.id() + "' in 'drivers'");
            rejectUnknownKeys(entry, DRIVER_KEYS, "driver '" + driver.id() + "'");
            return driver;
        });
    }

    /**
     * Parses the optional {@code repositories:} section. An absent section
     * yields an empty list (the resolver defaults to Maven Central); a section
     * that is present must declare at least one repository.
     */
    private static List<RepositoryEntry> parseRepositories(Map<String, Object> map) {
        Set<String> ids = new HashSet<>();
        List<RepositoryEntry> repositories = mapEntries(map.get("repositories"), "'repositories'", entry -> {
            RepositoryEntry repository = new RepositoryEntry(
                    string(entry, "id"),
                    string(entry, "url"),
                    string(entry, "username"),
                    string(entry, "password"));
            requireUnique(ids, repository.id(), "Duplicate repository id '" + repository.id() + "' in 'repositories'");
            rejectUnknownKeys(entry, REPOSITORY_KEYS, "repository '" + repository.id() + "'");
            return repository;
        });
        if (map.containsKey("repositories") && repositories.isEmpty()) {
            throw new IllegalStateException(
                    "'repositories' must define at least one repository (remove the section to use Maven Central)");
        }
        return repositories;
    }

    /**
     * Fails fast on a driver {@code repository:} that references no known
     * repository. With no {@code repositories:} section the implicit Maven
     * Central repository id is known.
     */
    private static void rejectUnknownRepositoryReferences(List<DriverEntry> drivers, List<RepositoryEntry> repositories) {
        List<String> known = repositories.isEmpty()
                ? List.of(RepositoryEntry.DEFAULT_ID)
                : repositories.stream().map(RepositoryEntry::id).toList();
        for (DriverEntry driver : drivers) {
            if (driver.repository() != null && !known.contains(driver.repository())) {
                throw new IllegalStateException("Driver '" + driver.id() + "' references unknown repository '"
                        + driver.repository() + "'; known repositories: " + known);
            }
        }
    }

    private static List<MavenCoord> parseMavenCoords(Object value, String driverId) {
        return mapEntries(value, "'maven'", coord -> {
            rejectUnknownKeys(coord, MAVEN_KEYS, "a 'maven' entry of driver '" + driverId + "'");
            return new MavenCoord(
                    string(coord, "groupId"),
                    string(coord, "artifactId"),
                    string(coord, "version"));
        });
    }

    private static List<ToolEntry> parseTools(Map<String, Object> map, List<SourceEntry> sources) {
        Set<String> bindings = new HashSet<>();
        return mapEntries(map.get("tools"), "'tools'", entry -> {
            String name = string(entry, "name");
            if (name == null || !KNOWN_TOOLS.contains(name)) {
                throw new IllegalStateException("Unknown tool '" + name + "' in 'tools'; known tools: "
                        + KNOWN_TOOLS.stream().sorted().toList());
            }
            ToolEntry tool = new ToolEntry(
                    name,
                    string(entry, "source"),
                    bool(entry, "readonly"),
                    integer(entry, "maxRows"));
            if (sources.stream().noneMatch(s -> s.id().equals(tool.source()))) {
                throw new IllegalStateException("Tool '" + tool.name() + "' references unknown source '" + tool.source()
                        + "'; known sources: " + sources.stream().map(SourceEntry::id).toList());
            }
            requireUnique(bindings, tool.name() + "@" + tool.source(),
                    "Duplicate tool '" + tool.name() + "' for source '" + tool.source() + "'");
            rejectUnknownKeys(entry, TOOL_KEYS, "tool '" + tool.name() + "'");
            return tool;
        });
    }

    private static void rejectUnknownKeys(Map<String, Object> entry, Set<String> allowed, String context) {
        for (String key : entry.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalStateException("Unknown key '" + key + "' in " + context + "; allowed keys: "
                        + allowed.stream().sorted().toList());
            }
        }
    }

    private static void requireUnique(Set<String> seen, String key, String message) {
        if (!seen.add(key)) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Interpolate {@code ${VAR}} references from the process environment.
     * Unresolved variables are kept as-is, mirroring dbhub's
     * {@code interpolateEnvVars}.
     */
    public static String interpolate(String value) {
        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String envValue = System.getenv(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(envValue != null ? envValue : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private static <T> List<T> mapEntries(Object value, String key, Function<Map<String, Object>, T> mapper) {
        List<Map<String, Object>> raw = objectList(value, key);
        List<T> result = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            result.add(mapper.apply(entry));
        }
        return result;
    }

    private static List<Map<String, Object>> objectList(Object value, String key) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(key + " must be a YAML list");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new IllegalStateException("Every entry of " + key + " must be a YAML mapping, got: " + item);
            }
            result.add(stringKeyed(itemMap));
        }
        return result;
    }

    private static String string(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        return value == null ? null : interpolate(String.valueOf(value));
    }

    private static Integer integer(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return intValue(number, key, value);
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw notAnInteger(key, value);
        }
    }

    private static int intValue(Number number, String key, Object value) {
        if (number instanceof Long longValue) {
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                throw new IllegalStateException("Key '" + key + "' is out of range for an integer, got: " + value);
            }
            return longValue.intValue();
        }
        if (number instanceof Integer || number instanceof Short || number instanceof Byte) {
            return number.intValue();
        }
        throw notAnInteger(key, value);
    }

    private static IllegalStateException notAnInteger(String key, Object value) {
        return new IllegalStateException("Key '" + key + "' must be an integer, got: " + value);
    }

    private static Boolean bool(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException("Key '" + key + "' must be a boolean, got: " + value);
    }
}
