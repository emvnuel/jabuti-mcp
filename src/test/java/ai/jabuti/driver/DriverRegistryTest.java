package ai.jabuti.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import ai.jabuti.config.DriverEntry;
import ai.jabuti.config.JdbcMcpConfig;
import ai.jabuti.config.MavenCoord;
import ai.jabuti.config.SourceEntry;
import org.junit.jupiter.api.Test;

class DriverRegistryTest {

    private static JdbcMcpConfig config(SourceEntry source, DriverEntry... drivers) {
        return new JdbcMcpConfig(List.of(source), List.of(drivers), List.of(), List.of());
    }

    private static SourceEntry source(String driver) {
        return new SourceEntry("pg", "jdbc:postgresql://localhost:5432/app", "u", "p", driver, null);
    }

    @Test
    void resolvesBuiltinDriver() {
        DriverRegistry registry = new DriverRegistry(config(source("postgres-jdbc")));

        DriverDescriptor descriptor = registry.require("postgres-jdbc");
        assertEquals("org.postgresql.Driver", descriptor.className());
        assertEquals("org.postgresql:postgresql:42.7.14", descriptor.coordinates());
        assertTrue(registry.knownIds().containsAll(List.of(
                "postgres-jdbc", "mysql-jdbc", "mariadb-jdbc", "sqlite-jdbc", "mssql-jdbc", "oracle-jdbc",
                "hsqldb-jdbc", "derby-jdbc", "firebird-jdbc", "hana-jdbc", "informix-jdbc", "duckdb-jdbc")));
    }

    @Test
    void yamlOverridesBuiltinVersionAndInheritsClassName() {
        DriverEntry override = new DriverEntry("postgres-jdbc", null,
                List.of(new MavenCoord("org.postgresql", "postgresql", "42.7.13")), null);
        DriverRegistry registry = new DriverRegistry(config(source("postgres-jdbc"), override));

        DriverDescriptor descriptor = registry.require("postgres-jdbc");
        assertEquals("org.postgresql.Driver", descriptor.className());
        assertEquals("org.postgresql:postgresql:42.7.13", descriptor.coordinates());
    }

    @Test
    void builtinDriverHasNoPinnedRepository() {
        DriverRegistry registry = new DriverRegistry(config(source("postgres-jdbc")));

        assertNull(registry.require("postgres-jdbc").repository());
    }

    @Test
    void yamlOverridePropagatesPinnedRepository() {
        DriverEntry override = new DriverEntry("postgres-jdbc", null, List.of(), "internal");
        DriverRegistry registry = new DriverRegistry(config(source("postgres-jdbc"), override));

        assertEquals("internal", registry.require("postgres-jdbc").repository());
    }

    @Test
    void registersCustomDriver() {
        DriverEntry custom = new DriverEntry("exotic-jdbc", "com.exotic.Driver",
                List.of(new MavenCoord("com.exotic", "driver", "1.2.3")), null);
        DriverRegistry registry = new DriverRegistry(config(source("exotic-jdbc"), custom));

        assertEquals("com.exotic.Driver", registry.require("exotic-jdbc").className());
    }

    @Test
    void rejectsCustomDriverWithoutCoordinates() {
        DriverEntry custom = new DriverEntry("exotic-jdbc", "com.exotic.Driver", List.of(), null);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DriverRegistry(config(source("exotic-jdbc"), custom)));
        assertTrue(error.getMessage().contains("maven"), error.getMessage());
    }

    @Test
    void rejectsSourceWithUnknownDriver() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DriverRegistry(config(source("ghost-jdbc"))));
        assertTrue(error.getMessage().contains("unknown driver 'ghost-jdbc'"), error.getMessage());
        assertTrue(error.getMessage().contains("postgres-jdbc"), error.getMessage());
    }

    @Test
    void requireUnknownDriverListsKnownIds() {
        DriverRegistry registry = new DriverRegistry(config(source("postgres-jdbc")));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> registry.require("nope"));
        assertTrue(error.getMessage().contains("Known drivers"), error.getMessage());
    }
}
