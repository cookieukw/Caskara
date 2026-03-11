package com.cookie.caskara;

import com.cookie.caskara.db.Shell;
import org.junit.jupiter.api.*;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Shell class — connection lifecycle, schema, and thread-safety.
 */
class ShellTest {

    private Shell shell;

    @BeforeEach
    void setup() {
        shell = new Shell(createTempDb());
    }

    @AfterEach
    void teardown() {
        shell.close();
    }

    @Test
    @DisplayName("Shell initializes a valid, open SQLite connection")
    void testConnectionIsOpen() throws Exception {
        Connection conn = shell.getConnection();
        assertNotNull(conn);
        assertFalse(conn.isClosed());
    }

    @Test
    @DisplayName("Shell creates the 'elements' table on init")
    void testElementsTableExists() throws Exception {
        Connection conn = shell.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='elements'")) {
            assertTrue(rs.next(), "Expected 'elements' table to exist");
        }
    }

    @Test
    @DisplayName("Shell creates the idx_type index on init")
    void testTypeIndexExists() throws Exception {
        Connection conn = shell.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_type'")) {
            assertTrue(rs.next(), "Expected 'idx_type' index to exist");
        }
    }

    @Test
    @DisplayName("Core is created and reused from cache")
    void testCoreReuse() {
        var core1 = shell.core(User.class);
        var core2 = shell.core(User.class);
        assertSame(core1, core2, "Same Core instance should be returned for the same class");
    }

    // Simple internal entity for testing
    static class User {
        public String id;
        public String name;
        public User() {}
        public User(String name) { this.name = name; }
    }

    static File createTempDb() {
        try {
            File f = File.createTempFile("caskara-test-", ".db");
            f.deleteOnExit();
            return f;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
