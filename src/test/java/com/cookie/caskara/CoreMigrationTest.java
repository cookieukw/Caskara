package com.cookie.caskara;

import com.cookie.caskara.db.Core;
import com.cookie.caskara.db.Shell;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the schema migration system.
 * Verifies that migrations are applied lazily on read and the record is re-saved at the new version.
 */
class CoreMigrationTest {

    private Shell shell;
    private Core<PlayerV2> core;

    @BeforeEach
    void setup() throws Exception {
        File tempDb = File.createTempFile("caskara-migration-test-", ".db");
        tempDb.deleteOnExit();
        shell = new Shell(tempDb);
        core = shell.core(PlayerV2.class);
    }

    @AfterEach
    void teardown() {
        shell.close();
    }

    @Test
    @DisplayName("Entity without migrations is read as-is")
    void testNoMigration() {
        core.preserve("p1", new PlayerV2("Alice", 5, "warrior"));
        PlayerV2 loaded = core.extract("p1").sync().orElse(null);
        assertNotNull(loaded);
        assertEquals("Alice", loaded.name);
        assertEquals("warrior", loaded.role);
    }

    @Test
    @DisplayName("registerMigration() transforms JSON when reading old-version record")
    void testMigrationApplied() throws Exception {
        // Save a "v1" record directly into the DB (simulating old data before migration)
        injectRawRecord("p2", "{\"id\":\"p2\",\"name\":\"Bob\",\"level\":3}", 1);

        // Register a v2 migration: add a "role" field with default "novice"
        core.registerMigration(2, json -> {
            if (!json.has("role")) {
                json.addProperty("role", "novice");
            }
            return json;
        });

        // On next read, migration should be applied
        PlayerV2 loaded = core.extract("p2").sync().orElse(null);
        assertNotNull(loaded, "Entity should be loaded after migration");
        assertEquals("novice", loaded.role, "Migration should have added 'role' field");
    }

    @Test
    @DisplayName("Migration is chained: multiple versions are applied in order")
    void testChainedMigrations() throws Exception {
        // Old data at version 1
        injectRawRecord("p3", "{\"id\":\"p3\",\"name\":\"Carol\"}", 1);

        // v2: add level
        core.registerMigration(2, json -> {
            json.addProperty("level", 1);
            return json;
        });

        // v3: add role
        core.registerMigration(3, json -> {
            json.addProperty("role", "knight");
            return json;
        });

        PlayerV2 loaded = core.extract("p3").sync().orElse(null);
        assertNotNull(loaded);
        assertEquals(1, loaded.level);
        assertEquals("knight", loaded.role);
    }

    /**
     * Helper: inserts a raw JSON record at a specific version number, bypassing Core logic.
     */
    private void injectRawRecord(String id, String json, int version) throws Exception {
        try (var pstmt = shell.getConnection().prepareStatement(
                "INSERT INTO elements (id, type, json, version) VALUES (?, ?, ?, ?)")) {
            pstmt.setString(1, id);
            pstmt.setString(2, "playerv2"); // typeName = class simple name lowercased
            pstmt.setString(3, json);
            pstmt.setInt(4, version);
            pstmt.executeUpdate();
        }
    }

    // --- Entity (represents a "v2" player with a role field) ---
    static class PlayerV2 {
        public String id;
        public String name;
        public int level;
        public String role;
        public PlayerV2() {}
        public PlayerV2(String name, int level, String role) {
            this.name = name;
            this.level = level;
            this.role = role;
        }
    }
}
