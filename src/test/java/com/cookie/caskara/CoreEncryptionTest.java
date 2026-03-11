package com.cookie.caskara;

import com.cookie.caskara.db.Core;
import com.cookie.caskara.db.Shell;
import org.junit.jupiter.api.*;

import java.io.File;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Core encryption: ensures data is stored encrypted and
 * decrypted transparently on load.
 */
class CoreEncryptionTest {

    private Shell shell;
    private Core<Secret> core;

    @BeforeEach
    void setup() {
        shell = new Shell(createTempDb());
        core = shell.core(Secret.class);
        core.setSecurityKey("my-secret-password");
    }

    @AfterEach
    void teardown() {
        shell.close();
    }

    @Test
    @DisplayName("Encrypted data is stored as Base64, not plaintext")
    void testDataIsEncryptedInDb() throws Exception {
        core.preserve("enc-1", new Secret("top-secret-token"));

        // Query the raw SQLite value
        try (Statement stmt = shell.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT json FROM elements WHERE id = 'enc-1'")) {
            assertTrue(rs.next());
            String raw = rs.getString("json");
            assertFalse(raw.contains("top-secret-token"),
                    "Raw DB value should NOT contain plaintext token");
        }
    }

    @Test
    @DisplayName("Encrypted entity is transparently decrypted on load")
    void testDecryptionOnLoad() {
        core.preserve("enc-2", new Secret("my-api-key"));
        Secret loaded = core.extract("enc-2").sync().orElse(null);
        assertNotNull(loaded);
        assertEquals("my-api-key", loaded.token,
                "Loaded entity should have the original plaintext value");
    }

    @Test
    @DisplayName("Different keys produce different ciphertext (key derivation sanity check)")
    void testDifferentKeys() throws Exception {
        core.preserve("enc-3", new Secret("hello"));
        String raw1 = getRawJson("enc-3");

        Shell shell2 = new Shell(createTempDb());
        Core<Secret> core2 = shell2.core(Secret.class);
        core2.setSecurityKey("different-password");
        core2.preserve("enc-3", new Secret("hello"));
        String raw2 = getRawJson(shell2, "enc-3");
        shell2.close();

        assertNotEquals(raw1, raw2, "Different keys should produce different ciphertext");
    }

    private String getRawJson(String id) throws Exception {
        return getRawJson(shell, id);
    }

    private String getRawJson(Shell s, String id) throws Exception {
        try (Statement stmt = s.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT json FROM elements WHERE id = '" + id + "'")) {
            return rs.next() ? rs.getString("json") : null;
        }
    }

    // --- Entity ---
    static class Secret {
        public String id;
        public String token;
        public Secret() {}
        public Secret(String token) { this.token = token; }
    }

    static java.io.File createTempDb() {
        try {
            java.io.File f = java.io.File.createTempFile("caskara-test-", ".db");
            f.deleteOnExit();
            return f;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
