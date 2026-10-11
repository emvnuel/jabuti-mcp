package ai.jabuti.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToolNamesTest {

    @Test
    void normalizesSourceIds() {
        assertEquals("prod_db", ToolNames.normalizeSourceId("prod_db"));
        assertEquals("staging_db", ToolNames.normalizeSourceId("staging-db"));
        assertEquals("dev_db", ToolNames.normalizeSourceId("dev.db"));
        assertEquals("my_db_123", ToolNames.normalizeSourceId("my@db#123"));
    }

    @Test
    void singleSourceKeepsBareToolName() {
        assertEquals("execute_sql", ToolNames.toolName("execute_sql", "staging-db", true));
        assertEquals("search_objects", ToolNames.toolName("search_objects", "my@db#123", true));
    }

    @Test
    void multipleSourcesSuffixWithNormalizedId() {
        assertEquals("execute_sql_staging_db", ToolNames.toolName("execute_sql", "staging-db", false));
        assertEquals("search_objects_my_db_123", ToolNames.toolName("search_objects", "my@db#123", false));
    }
}
