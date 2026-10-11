package ai.jabuti.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import ai.jabuti.testsupport.H2Sources;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DatasourceManagerTest {

    private DatasourceManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void connectsAndProbesWithSelectOne() throws Exception {
        manager = H2Sources.uniqueManager();

        manager.ensureConnected("h2db");

        HikariDataSource pool = manager.poolFor("h2db");
        assertFalse(pool.isClosed());
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    @Test
    void rejectsUnknownSourceListingKnownOnes() {
        manager = H2Sources.uniqueManager();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.ensureConnected("ghost"));
        assertTrue(error.getMessage().contains("Unknown source 'ghost'"), error.getMessage());
        assertTrue(error.getMessage().contains("h2db"), error.getMessage());
    }

    @Test
    void sqliteIsPinnedToOneConnection() {
        assertEquals(1, DatasourceManager.effectiveMaxPoolSize("jdbc:sqlite:/tmp/app.db", 10));
        assertEquals(1, DatasourceManager.effectiveMaxPoolSize("jdbc:sqlite::memory:", 10));
        assertEquals(10, DatasourceManager.effectiveMaxPoolSize("jdbc:postgresql://localhost/app", 10));
    }
}
