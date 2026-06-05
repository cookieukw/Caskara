package com.cookie.caskara;

import com.cookie.caskara.annotations.CaskaraEntity;
import com.cookie.caskara.annotations.Encrypted;
import com.cookie.caskara.annotations.FullTextSearch;
import com.cookie.caskara.annotations.Id;
import com.cookie.caskara.exceptions.DatabaseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FtsQueryTest {
    private File tempDir;

    @FullTextSearch
    @CaskaraEntity(shell = "fts_test")
    public static class ChatMessage {
        @Id
        public String id;
        public String sender;
        public String text;
        
        public ChatMessage(String id, String sender, String text) {
            this.id = id;
            this.sender = sender;
            this.text = text;
        }
    }

    @FullTextSearch
    @Encrypted
    @CaskaraEntity(shell = "fts_invalid")
    public static class InvalidEntity {
        @Id
        public String id;
    }

    @CaskaraEntity(shell = "fts_unindexed")
    public static class UnindexedEntity {
        @Id
        public String id;
    }

    @BeforeEach
    void setup() {
        tempDir = new File(System.getProperty("java.io.tmpdir"), "caskara-fts-" + System.currentTimeMillis());
        tempDir.mkdirs();
        Caskara.init(tempDir);
    }

    @AfterEach
    void teardown() {
        if (tempDir != null && tempDir.exists()) {
            File global = new File(tempDir, "global");
            if (global.exists()) {
                File[] files = global.listFiles();
                if (files != null) {
                    for (File f : files) f.delete();
                }
                global.delete();
            }
            tempDir.delete();
        }
    }

    @Test
    @DisplayName("search() throws DatabaseException if entity lacks @FullTextSearch")
    void testSearchWithoutAnnotation() {
        Caskara.save(new UnindexedEntity());
        assertThrows(DatabaseException.class, () -> {
            Caskara.query(UnindexedEntity.class).search("anything").fetch();
        });
    }

    @Test
    @DisplayName("Caskara.register() throws DatabaseException if entity has both @FullTextSearch and @Encrypted")
    void testIncompatibleAnnotations() {
        assertThrows(DatabaseException.class, () -> {
            Caskara.register(InvalidEntity.class);
        });
    }

    @Test
    @DisplayName("search() correctly matches text using FTS5")
    void testFtsMatch() {
        Caskara.save(new ChatMessage("1", "Cookie", "Hello everyone, welcome to the server!"));
        Caskara.save(new ChatMessage("2", "Alice", "Does anyone have some spare diamonds?"));
        Caskara.save(new ChatMessage("3", "Bob", "I lost all my diamonds in lava..."));
        Caskara.save(new ChatMessage("4", "Cookie", "Here, take these emeralds instead."));

        // Test matching a single word
        List<ChatMessage> diamondMessages = Caskara.query(ChatMessage.class)
                .search("diamonds")
                .fetch();
        
        assertEquals(2, diamondMessages.size());
        assertTrue(diamondMessages.stream().anyMatch(m -> m.sender.equals("Alice")));
        assertTrue(diamondMessages.stream().anyMatch(m -> m.sender.equals("Bob")));

        // Test matching another word
        List<ChatMessage> cookieMessages = Caskara.query(ChatMessage.class)
                .search("welcome")
                .fetch();
        
        assertEquals(1, cookieMessages.size());
        assertEquals("1", cookieMessages.get(0).id);
    }
}
