package ai.jabuti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;

/**
 * End-to-end test: boots the packaged server over stdio against real
 * PostgreSQL 16 and MySQL 8.4 containers, with a temporary YAML config and a
 * temporary drivers directory (the real {@code ~/.jabuti} is never touched).
 * Drivers are downloaded from Maven Central by the server itself.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PostgreSQLContainer<?> postgres;
    private static LogWaitedMysqlContainer mysql;
    private static StdioServerHarness client;
    private static Path driversDir;
    private static JsonNode toolsList;

    @BeforeAll
    static void startServer() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("app").withUsername("app").withPassword("app");
        mysql = new LogWaitedMysqlContainer("mysql:8.4")
                .withDatabaseName("app").withUsername("app").withPassword("app");
        postgres.start();
        mysql.start();

        seedPostgres();
        seedMysql();

        Path tempDir = Files.createTempDirectory("jabuti-mcp-it");
        driversDir = tempDir.resolve("drivers");
        Path configFile = tempDir.resolve("datasources.yaml");
        Files.writeString(configFile, yamlConfig());

        client = StdioServerHarness.start(configFile, driversDir, "server-it");
        toolsList = client.rpc("tools/list", Map.of()).path("result").path("tools");
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (client != null) {
            client.close();
        }
        if (mysql != null) {
            mysql.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void listsToolsWithNormalizedSourceSuffixes() {
        List<String> names = new ArrayList<>();
        toolsList.forEach(tool -> names.add(tool.get("name").asText()));
        assertTrue(names.containsAll(List.of(
                "execute_sql_pg", "execute_sql_my", "search_objects_pg", "search_objects_my",
                "explain_sql_pg", "health_check_pg", "database_info_pg")), names.toString());
    }

    @Test
    void exposesToolAnnotations() {
        JsonNode readonlySql = tool("execute_sql_pg");
        assertTrue(readonlySql.path("annotations").path("readOnlyHint").asBoolean(), readonlySql.toString());
        assertFalse(readonlySql.path("annotations").path("destructiveHint").asBoolean(), readonlySql.toString());
        assertEquals("Execute SQL (read-only)", readonlySql.path("title").asText(), readonlySql.toString());

        JsonNode writableSql = tool("execute_sql_pgw");
        assertFalse(writableSql.path("annotations").path("readOnlyHint").asBoolean(), writableSql.toString());
        assertTrue(writableSql.path("annotations").path("destructiveHint").asBoolean(), writableSql.toString());

        assertTrue(tool("search_objects_pg").path("annotations").path("readOnlyHint").asBoolean());
        assertTrue(tool("database_info_pg").path("annotations").path("readOnlyHint").asBoolean());
    }

    @Test
    void listsAndRendersPrompts() throws Exception {
        JsonNode prompts = client.rpc("prompts/list", Map.of()).path("result").path("prompts");
        List<String> names = new ArrayList<>();
        prompts.forEach(prompt -> names.add(prompt.get("name").asText()));
        assertTrue(names.containsAll(List.of("explore_database", "er_diagram")), names.toString());

        JsonNode get = client.rpc("prompts/get",
                Map.of("name", "explore_database", "arguments", Map.of("source", "pg")));
        String text = promptText(get);
        assertTrue(text.contains("source 'pg'"), get.toString());
        assertTrue(text.contains("search_objects"), get.toString());
    }

    @Test
    void rendersPromptsForMissingAndNullSourceArguments() throws Exception {
        // The shapes clients send when the optional argument is left unset:
        // no "arguments" key at all, or an explicit JSON null.
        Map<String, Object> nullSource = new LinkedHashMap<>();
        nullSource.put("source", null);

        for (String prompt : List.of("explore_database", "er_diagram")) {
            JsonNode noArguments = client.rpc("prompts/get", Map.of("name", prompt));
            assertFalse(noArguments.has("error"), prompt + " without arguments failed: " + noArguments);
            assertTrue(promptText(noArguments).contains("Available sources: pg, pgw, my"), noArguments.toString());

            JsonNode explicitNull = client.rpc("prompts/get", Map.of("name", prompt, "arguments", nullSource));
            assertFalse(explicitNull.has("error"), prompt + " with a null source failed: " + explicitNull);
            assertTrue(promptText(explicitNull).contains("Available sources: pg, pgw, my"), explicitNull.toString());
        }
    }

    private static String promptText(JsonNode response) {
        return response.path("result").path("messages").get(0).path("content").path("text").asText();
    }

    @Test
    void executesSelectOnPostgres() throws Exception {
        JsonNode envelope = callTool("execute_sql_pg", Map.of("sql", "SELECT 1 AS one"));

        assertTrue(envelope.get("success").asBoolean(), envelope.toString());
        JsonNode statements = envelope.get("data").get("statements");
        assertEquals(1, statements.size());
        assertEquals(1, statements.get(0).get("rows").size());
        assertEquals("pg", envelope.get("data").get("source_id").asText());
    }

    @Test
    void deniesDeleteOnReadonlySource() throws Exception {
        JsonNode envelope = callTool("execute_sql_pg", Map.of("sql", "DELETE FROM items"));

        assertFalse(envelope.get("success").asBoolean(), envelope.toString());
        assertEquals("READONLY_VIOLATION", envelope.get("code").asText());
    }

    @Test
    void deniesDataModifyingCteOnReadonlyPostgres() throws Exception {
        JsonNode envelope = callTool("execute_sql_pg", Map.of("sql",
                "WITH deleted AS (DELETE FROM items RETURNING id) SELECT id FROM deleted"));

        assertFalse(envelope.get("success").asBoolean(), envelope.toString());
        assertEquals("READONLY_VIOLATION", envelope.get("code").asText());

        JsonNode parenthesized = callTool("execute_sql_pg", Map.of("sql",
                "(WITH deleted AS (DELETE FROM items RETURNING id) SELECT id FROM deleted)"));

        assertFalse(parenthesized.get("success").asBoolean(), parenthesized.toString());
        assertEquals("READONLY_VIOLATION", parenthesized.get("code").asText());
    }

    @Test
    void executesDataModifyingCteOnWritablePostgres() throws Exception {
        JsonNode insert = callTool("execute_sql_pgw", Map.of("sql",
                "WITH inserted AS (INSERT INTO items (id, name) VALUES (6, 'f') RETURNING id) SELECT id FROM inserted"));

        assertTrue(insert.get("success").asBoolean(), insert.toString());
        assertEquals(1, insert.get("data").get("statements").get(0).get("rows").size());

        JsonNode delete = callTool("execute_sql_pgw", Map.of("sql", "DELETE FROM items WHERE id = 6"));
        assertTrue(delete.get("success").asBoolean(), delete.toString());
        assertEquals(1, delete.get("data").get("statements").get(0).get("rowCount").asInt());
    }

    @Test
    void truncatesBeyondMaxRows() throws Exception {
        JsonNode envelope = callTool("execute_sql_pg", Map.of("sql", "SELECT id FROM items ORDER BY id"));

        JsonNode statement = envelope.get("data").get("statements").get(0);
        assertEquals(3, statement.get("rows").size());
        assertEquals(3, statement.get("rowCount").asInt());
        assertTrue(statement.get("truncated").asBoolean(), statement.toString());
    }

    @Test
    void searchObjectsFullReturnsColumnsAndIndexesOnPostgres() throws Exception {
        JsonNode envelope = callTool("search_objects_pg", Map.of(
                "object_type", "table",
                "schema", "public",
                "pattern", "items",
                "detail_level", "full"));

        assertTrue(envelope.get("success").asBoolean(), envelope.toString());
        JsonNode result = envelope.get("data").get("results").get(0);
        assertEquals("items", result.get("name").asText());

        List<String> columns = new ArrayList<>();
        result.get("columns").forEach(column -> columns.add(column.get("name").asText()));
        assertTrue(columns.containsAll(List.of("id", "name")), columns.toString());

        boolean hasPrimary = false;
        for (JsonNode index : result.get("indexes")) {
            if (index.get("primary").asBoolean()) {
                hasPrimary = true;
            }
        }
        assertTrue(hasPrimary, "expected a primary index: " + result.get("indexes"));
        assertEquals(5, result.get("row_count").asInt());
    }

    @Test
    void explainNeverExecutesTheTargetStatement() throws Exception {
        JsonNode envelope = callTool("explain_sql_pg", Map.of("sql", "DELETE FROM items WHERE id = 4"));

        assertTrue(envelope.get("success").asBoolean(), envelope.toString());
        JsonNode data = envelope.get("data");
        assertTrue(data.get("count").asInt() > 0, envelope.toString());
        assertTrue(planText(data).contains("items"), envelope.toString());

        JsonNode after = callTool("execute_sql_pg", Map.of("sql", "SELECT id FROM items WHERE id = 4"));
        assertEquals(1, after.get("data").get("statements").get(0).get("rows").size(),
                "explain must never execute the target statement: " + after);
    }

    @Test
    void explainRejectsNestedExplainInput() throws Exception {
        JsonNode envelope = callTool("explain_sql_pg", Map.of("sql", "EXPLAIN SELECT 1"));

        assertFalse(envelope.get("success").asBoolean(), envelope.toString());
        assertEquals("INVALID_INPUT", envelope.get("code").asText());
    }

    @Test
    void reportsHealthOnPostgres() throws Exception {
        JsonNode envelope = callTool("health_check_pg", Map.of());

        assertTrue(envelope.get("success").asBoolean(), envelope.toString());
        JsonNode data = envelope.get("data");
        assertEquals("pg", data.get("source_id").asText());
        assertTrue(data.get("database").get("product").asText().contains("PostgreSQL"), envelope.toString());
        assertTrue(data.get("probe").get("select1_ok").asBoolean(), envelope.toString());
        assertTrue(data.get("connections").get("total").asInt() >= 1, envelope.toString());
    }

    @Test
    void reportsDatabaseInfoOnPostgres() throws Exception {
        JsonNode envelope = callTool("database_info_pg", Map.of());

        assertTrue(envelope.get("success").asBoolean(), envelope.toString());
        JsonNode data = envelope.get("data");
        assertEquals("pg", data.get("source_id").asText());
        assertTrue(data.get("product").asText().contains("PostgreSQL"), envelope.toString());
        assertEquals("PG", data.get("family").asText());
        assertEquals("\"", data.get("identifier_quote").asText());
        assertEquals("schema", data.get("schema_term").asText());
        assertFalse(data.get("read_only").asBoolean(), envelope.toString());
        assertTrue(data.get("supports_transactions").asBoolean(), envelope.toString());
        assertTrue(data.has("sql_keywords"), envelope.toString());
    }

    private static String planText(JsonNode data) {
        StringBuilder plan = new StringBuilder();
        data.get("rows").forEach(row -> row.forEach(value -> plan.append(value.asText())));
        return plan.toString();
    }

    @Test
    void executesSelectOnMysql() throws Exception {
        JsonNode envelope = callTool("execute_sql_my", Map.of("sql", "SELECT 1 AS one"));

        assertTrue(envelope.get("success").asBoolean(), envelope.toString());
        assertEquals(1, envelope.get("data").get("statements").get(0).get("rows").size());
    }

    @Test
    void deniesDeleteOnReadonlyMysqlSource() throws Exception {
        JsonNode envelope = callTool("execute_sql_my", Map.of("sql", "DELETE FROM items"));

        assertFalse(envelope.get("success").asBoolean(), envelope.toString());
        assertEquals("READONLY_VIOLATION", envelope.get("code").asText());
    }

    @Test
    void searchObjectsFullReturnsColumnsAndIndexesOnMysql() throws Exception {
        JsonNode envelope = callTool("search_objects_my", Map.of(
                "object_type", "table",
                "schema", "app",
                "pattern", "items",
                "detail_level", "full"));

        assertTrue(envelope.get("success").asBoolean(), envelope.toString());
        JsonNode result = envelope.get("data").get("results").get(0);
        assertEquals("items", result.get("name").asText());

        List<String> columns = new ArrayList<>();
        result.get("columns").forEach(column -> columns.add(column.get("name").asText()));
        assertTrue(columns.containsAll(List.of("id", "name")), columns.toString());
        assertEquals(5, result.get("row_count").asInt());
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void downloadedDriversIntoConfiguredDirectory() throws Exception {
        assertTrue(containsJar(driversDir.resolve("org/postgresql/postgresql"), "postgresql-"),
                "postgres driver jar not found under " + driversDir);
        assertTrue(containsJar(driversDir.resolve("com/mysql/mysql-connector-j"), "mysql-connector-j-"),
                "mysql driver jar not found under " + driversDir);
    }

    /**
     * Whether any downloaded version under {@code artifactDir} holds a jar
     * named {@code <jarPrefix><version>.jar}. The catalog pins the versions;
     * this test only checks that the drivers arrived.
     */
    private static boolean containsJar(Path artifactDir, String jarPrefix) throws Exception {
        if (!Files.isDirectory(artifactDir)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(artifactDir)) {
            return paths.anyMatch(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(jarPrefix) && name.endsWith(".jar");
            });
        }
    }

    private static JsonNode callTool(String name, Map<String, Object> arguments) throws Exception {
        JsonNode response = client.rpc("tools/call", Map.of("name", name, "arguments", arguments));
        assertFalse(response.has("error"), "tools/call failed: " + response);
        JsonNode result = response.path("result");
        String text = result.path("content").get(0).path("text").asText();
        JsonNode envelope = MAPPER.readTree(text);
        assertEquals(!envelope.path("success").asBoolean(), result.path("isError").asBoolean(),
                "isError must mirror the envelope's success flag: " + response);
        return envelope;
    }

    private static JsonNode tool(String name) {
        for (JsonNode tool : toolsList) {
            if (name.equals(tool.get("name").asText())) {
                return tool;
            }
        }
        throw new IllegalStateException("tool not found in tools/list: " + name);
    }

    private static String yamlConfig() {
        return """
                sources:
                  - id: pg
                    url: "%s"
                    username: %s
                    password: %s
                    driver: postgres-jdbc
                  - id: pgw
                    url: "%s"
                    username: %s
                    password: %s
                    driver: postgres-jdbc
                  - id: my
                    url: "%s"
                    username: %s
                    password: %s
                    driver: mysql-jdbc
                tools:
                  - { name: execute_sql, source: pg, readonly: true, maxRows: 3 }
                  - { name: search_objects, source: pg }
                  - { name: explain_sql, source: pg }
                  - { name: health_check, source: pg }
                  - { name: database_info, source: pg }
                  - { name: execute_sql, source: pgw, readonly: false, maxRows: 100 }
                  - { name: execute_sql, source: my, readonly: true, maxRows: 100 }
                  - { name: search_objects, source: my }
                """.formatted(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                mysqlJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static String mysqlJdbcUrl() {
        String url = mysql.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "sslMode=DISABLED&allowPublicKeyRetrieval=true";
    }

    private static void seedPostgres() throws Exception {
        ExecResult result = postgres.execInContainer(
                "psql", "-v", "ON_ERROR_STOP=1", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                "-c", "CREATE TABLE items (id INT PRIMARY KEY, name TEXT NOT NULL); "
                        + "INSERT INTO items (id, name) VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,'e')");
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    private static void seedMysql() throws Exception {
        ExecResult result = mysql.execInContainer(
                "mysql", "-u" + mysql.getUsername(), "-p" + mysql.getPassword(), mysql.getDatabaseName(),
                "-e", "CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(50) NOT NULL); "
                        + "INSERT INTO items (id, name) VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,'e')");
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    /**
     * MySQLContainer's default readiness check opens a JDBC connection, which
     * would require a MySQL driver on the test classpath and mask the server's
     * own isolated class loader. Wait for the final server's log line instead.
     */
    static final class LogWaitedMysqlContainer extends MySQLContainer<LogWaitedMysqlContainer> {

        LogWaitedMysqlContainer(String dockerImageName) {
            super(dockerImageName);
        }

        @Override
        protected void waitUntilContainerStarted() {
            new LogMessageWaitStrategy()
                    .withRegEx(".*ready for connections.*port: 3306.*")
                    .withTimes(1)
                    .withStartupTimeout(Duration.ofMinutes(2))
                    .waitUntilReady(this);
        }
    }
}
