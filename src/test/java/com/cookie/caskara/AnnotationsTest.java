package com.cookie.caskara;

import com.cookie.caskara.annotations.*;
import com.cookie.caskara.db.Shell;
import com.cookie.caskara.exceptions.DatabaseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationsTest {

    private Shell defaultShell;

    @BeforeEach
    void setup() throws Exception {
        File tempDir = java.nio.file.Files.createTempDirectory("caskara-tests").toFile();
        tempDir.deleteOnExit();
        Caskara.init(tempDir);
        defaultShell = Caskara.shell();
    }

    @AfterEach
    void teardown() {
        defaultShell.close();
    }

    @Test
    @DisplayName("@Id annotation correctly syncs the ID to a custom field")
    void testIdAnnotation() {
        Caskara.register(CustomIdEntity.class);
        CustomIdEntity entity = new CustomIdEntity();
        entity.name = "Test";
        
        String savedId = Caskara.save(entity);
        assertNotNull(savedId);
        assertEquals(savedId, entity.myCustomKey, "The @Id annotated field should be synced with the generated ID");
    }

    @Test
    @DisplayName("@TTL annotation sets default expiration correctly")
    void testTtlAnnotation() throws InterruptedException {
        Caskara.register(TtlEntity.class);
        TtlEntity entity = new TtlEntity();
        
        String savedId = Caskara.save(entity); // Should use default TTL of 1 second
        
        // Should exist right now
        assertTrue(Caskara.load(savedId, TtlEntity.class) != null);
        
        // Wait for 1.1 seconds
        Thread.sleep(1100);
        
        // Should be expired
        assertNull(Caskara.load(savedId, TtlEntity.class), "Entity should be null because @TTL expired");
    }

    @Test
    @DisplayName("@Encrypted throws error if no key is provided")
    void testEncryptedAnnotationWithoutKey() {
        // We register the entity but do NOT call Caskara.encrypt()
        Caskara.register(SecureEntity.class);
        SecureEntity entity = new SecureEntity();
        
        DatabaseException exception = assertThrows(DatabaseException.class, () -> {
            Caskara.save(entity);
        });
        
        assertTrue(exception.getMessage().contains("marked with @Encrypted but no security key was provided"));
    }

    @Test
    @DisplayName("@Encrypted works successfully if key is provided")
    void testEncryptedAnnotationWithKey() {
        Caskara.encrypt(SecureEntity.class, "super-secret-key");
        SecureEntity entity = new SecureEntity();
        
        assertDoesNotThrow(() -> {
            Caskara.save(entity);
        });
    }

    // --- Test Entities ---
    
    static class CustomIdEntity {
        @Id
        public String myCustomKey;
        public String name;
    }

    @TTL(seconds = 1)
    static class TtlEntity {
        @Id
        public String id;
    }

    @Encrypted
    static class SecureEntity {
        @Id
        public String id;
    }
}
