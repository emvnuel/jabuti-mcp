package ai.jabuti.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Guards the Renovate custom manager in {@code renovate.json} against silently
 * drifting from the built-in catalog format: if the regex stops matching an
 * entry, Renovate stops bumping that driver without any error. Paths are
 * project-relative, as tests run with the project base directory as CWD.
 */
class RenovateCatalogConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path RENOVATE_CONFIG = Path.of("renovate.json");
    private static final Path CATALOG = Path.of("src", "main", "resources", "builtin-drivers.yaml");
    private static final String CATALOG_RESOURCE = "builtin-drivers.yaml";
    private static final Pattern HANDLEBARS = Pattern.compile("\\{\\{\\{\\s*(\\w+)\\s*\\}\\}\\}");

    @Test
    void renovateCustomManagerCapturesEveryBuiltinDriverCoordinate() throws IOException {
        JsonNode config = MAPPER.readTree(RENOVATE_CONFIG.toFile());
        String catalogPath = CATALOG.toString().replace('\\', '/');
        String catalogYaml = Files.readString(CATALOG);

        Set<String> captured = new LinkedHashSet<>();
        for (JsonNode manager : config.path("customManagers")) {
            if (!matchesFile(manager, catalogPath)) {
                continue;
            }
            String depNameTemplate = manager.path("depNameTemplate").asText();
            for (JsonNode matchString : manager.path("matchStrings")) {
                Matcher matcher = Pattern.compile(matchString.asText()).matcher(catalogYaml);
                while (matcher.find()) {
                    captured.add(resolveDepName(depNameTemplate, matcher) + ":" + group(matcher, "currentValue"));
                }
            }
        }

        assertFalse(captured.isEmpty(),
                "renovate.json no longer captures any entry of " + catalogPath
                        + " (update the custom manager regex)");

        Set<String> catalog = new LinkedHashSet<>();
        for (DriverEntry driver : DatasourceConfigLoader.loadBuiltinDrivers(CATALOG_RESOURCE)) {
            for (MavenCoord coordinate : driver.maven()) {
                catalog.add(coordinate.coordinates());
            }
        }
        assertEquals(catalog, captured,
                "renovate.json must capture every built-in driver coordinate, or Renovate silently"
                        + " stops bumping the missed drivers");
    }

    /**
     * Whether the custom manager applies to {@code path}, accepting both the
     * current {@code managerFilePatterns} key and the legacy {@code fileMatch}
     * one (Renovate migrates between them), as plain regex or {@code /regex/}.
     */
    private static boolean matchesFile(JsonNode manager, String path) {
        JsonNode patterns = manager.path("managerFilePatterns").isMissingNode()
                ? manager.path("fileMatch") : manager.path("managerFilePatterns");
        for (JsonNode pattern : patterns) {
            String expression = pattern.asText();
            if (expression.startsWith("/") && expression.endsWith("/")) {
                expression = expression.substring(1, expression.length() - 1);
            }
            if (Pattern.compile(expression).matcher(path).find()) {
                return true;
            }
        }
        return false;
    }

    /** Expands a Renovate template such as {@code {{{depNameGroup}}}:{{{depNameArtifact}}}. */
    private static String resolveDepName(String template, Matcher matcher) {
        StringBuilder name = new StringBuilder();
        Matcher handlebars = HANDLEBARS.matcher(template);
        while (handlebars.find()) {
            handlebars.appendReplacement(name,
                    Matcher.quoteReplacement(group(matcher, handlebars.group(1))));
        }
        handlebars.appendTail(name);
        return name.toString();
    }

    private static String group(Matcher matcher, String name) {
        try {
            return matcher.group(name);
        } catch (IllegalArgumentException e) {
            throw new AssertionError(
                    "the Renovate matchString is missing the '" + name + "' capture group: " + matcher.pattern(), e);
        }
    }
}
