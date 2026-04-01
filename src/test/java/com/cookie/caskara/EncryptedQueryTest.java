package com.cookie.caskara;

import com.cookie.caskara.db.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class EncryptedQueryTest {
    private File testFolder;

    @BeforeEach
    void setup() {
        testFolder = new File("build/test-data-" + System.currentTimeMillis());
        Caskara.init(testFolder);
        Caskara.encrypt(PlayerData.class, "secure-test-key");
    }

    @AfterEach
    void cleanup() {
        deleteDir(testFolder);
    }

    @Test
    void testEncryptedQuery() {
        // Save test data (automatically encrypted)
        Caskara.save("p1", new PlayerData("Alice", 100));
        Caskara.save("p2", new PlayerData("Bob", 200));
        Caskara.save("p3", new PlayerData("Charlie", 300));
        Caskara.save("p4", new PlayerData("David", 400));

        // Perform query (should trigger in-memory fallback and work!)
        List<PlayerData> richPlayers = Caskara.query(PlayerData.class)
                .fieldGreaterThan("balance", 250)
                .orderBy("balance", Query.Order.DESC)
                .fetch();

        assertEquals(2, richPlayers.size(), "Should find 2 players with balance > 250");
        assertEquals("David", richPlayers.get(0).getName(), "David should be first (DESC)");
        assertEquals("Charlie", richPlayers.get(1).getName(), "Charlie should be second");
    }

    @Test
    void testEncryptedContainsQuery() {
        Caskara.save("p1", new PlayerData("Apple", 10));
        Caskara.save("p2", new PlayerData("Banana", 20));
        Caskara.save("p3", new PlayerData("Cherry", 30));

        List<PlayerData> results = Caskara.query(PlayerData.class)
                .fieldContains("name", "an")
                .fetch();

        assertEquals(1, results.size(), "Should find only Banana (case-insensitive in-memory check)");
        assertEquals("Banana", results.get(0).getName());
    }

    public static class PlayerData {
        private String name;
        private int balance;

        public PlayerData() {}
        public PlayerData(String name, int balance) {
            this.name = name;
            this.balance = balance;
        }
        public String getName() { return name; }
        public int getBalance() { return balance; }
    }

    private void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }
}
