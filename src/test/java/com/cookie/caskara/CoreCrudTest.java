package com.cookie.caskara;

import com.cookie.caskara.db.Core;
import com.cookie.caskara.db.Shell;
import com.cookie.caskara.exceptions.ValidationException;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Core CRUD operations: preserve, extract, extractAll, discard,
 * TTL, soft-delete, restore, and validators.
 */
class CoreCrudTest {

    private Shell shell;
    private Core<Player> core;

    @BeforeEach
    void setup() {
        shell = new Shell(createTempDb());
        core = shell.core(Player.class);
    }

    @AfterEach
    void teardown() {
        shell.close();
    }

    @Test
    @DisplayName("preserve() saves an entity and returns a non-null ID")
    void testSaveReturnsId() {
        String id = core.preserve(new Player("Alice", 10));
        assertNotNull(id);
        assertFalse(id.isBlank());
    }

    @Test
    @DisplayName("extract() loads the saved entity by ID")
    void testSaveAndLoad() {
        Player p = new Player("Bob", 5);
        String id = core.preserve(p);
        Player loaded = core.extract(id).sync().orElse(null);
        assertNotNull(loaded);
        assertEquals("Bob", loaded.name);
        assertEquals(5, loaded.level);
    }

    @Test
    @DisplayName("preserve() with explicit ID uses that ID")
    void testSaveWithExplicitId() {
        Player p = new Player("Charlie", 3);
        String id = core.preserve("custom-id", p);
        assertEquals("custom-id", id);
        Player loaded = core.extract("custom-id").sync().orElse(null);
        assertNotNull(loaded);
    }

    @Test
    @DisplayName("extractAll() returns all saved entities")
    void testListAll() throws Exception {
        // Use a unique temp file so the fresh Shell is fully isolated from all other test instances.
        File tempDb = File.createTempFile("caskara-list-test-", ".db");
        tempDb.deleteOnExit();
        Shell freshShell = new Shell(tempDb);
        Core<Player> freshCore = freshShell.core(Player.class);
        freshCore.preserve(new Player("A", 1));
        freshCore.preserve(new Player("B", 2));
        freshCore.preserve(new Player("C", 3));
        List<Player> all = freshCore.extractAll();
        freshShell.close();
        assertEquals(3, all.size());
    }

    @Test
    @DisplayName("discard() removes entity; subsequent extract() returns empty")
    void testDelete() {
        String id = core.preserve(new Player("Dave", 7));
        core.discard(id);
        var result = core.extract(id).sync();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("extract() returns empty for a non-existent ID")
    void testLoadMissing() {
        var result = core.extract("nonexistent").sync();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("TTL: entity expires after deadline and is not returned")
    void testTTLExpiry() throws InterruptedException {
        // expire immediately (1 ms TTL from now)
        long expiresAt = System.currentTimeMillis() + 1;
        core.preserve("ttl-id", new Player("Expiring", 1), expiresAt);
        Thread.sleep(50); // wait for expiry
        var result = core.extract("ttl-id").sync();
        assertTrue(result.isEmpty(), "Entity should have expired");
    }

    @Test
    @DisplayName("TTL: entity is accessible before deadline")
    void testTTLNotYetExpired() {
        long expiresAt = System.currentTimeMillis() + 60_000; // 1 minute
        core.preserve("ttl-future", new Player("Future", 1), expiresAt);
        var result = core.extract("ttl-future").sync();
        assertTrue(result.isPresent(), "Entity should still be alive");
    }

    @Test
    @DisplayName("softDelete() hides entity without removing; restore() brings it back")
    void testSoftDeleteAndRestore() {
        String id = core.preserve(new Player("Eve", 9));
        core.softDelete(id);
        assertTrue(core.extract(id).sync().isEmpty(), "Soft-deleted entity should be hidden");
        core.restore(id);
        assertTrue(core.extract(id).sync().isPresent(), "Restored entity should be visible");
    }

    @Test
    @DisplayName("addValidator() throws ValidationException when predicate fails")
    void testValidator() {
        core.addValidator(p -> p.level > 0);
        assertThrows(ValidationException.class, () -> core.preserve(new Player("Invalid", 0)));
    }

    @Test
    @DisplayName("addValidator() allows save when predicate passes")
    void testValidatorPasses() {
        core.addValidator(p -> p.level > 0);
        assertDoesNotThrow(() -> core.preserve(new Player("Valid", 5)));
    }

    @Test
    @DisplayName("ID is synced back to entity id field after preserve()")
    void testIdSync() {
        Player p = new Player("Sync", 1);
        String id = core.preserve(p);
        assertEquals(id, p.id, "Player.id field should be synced after preserve");
    }

    // --- Entity ---
    static class Player {
        public String id;
        public String name;
        public int level;
        public Player() {}
        public Player(String name, int level) { this.name = name; this.level = level; }
    }

    static java.io.File createTempDb() {
        try {
            java.io.File f = java.io.File.createTempFile("caskara-test-", ".db");
            f.deleteOnExit();
            return f;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
