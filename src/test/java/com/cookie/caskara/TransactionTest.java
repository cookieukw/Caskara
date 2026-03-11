package com.cookie.caskara;

import com.cookie.caskara.db.Core;
import com.cookie.caskara.db.Shell;
import com.cookie.caskara.exceptions.DatabaseException;
import org.junit.jupiter.api.*;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ACID Transactions: atomic commit and rollback on failure.
 */
class TransactionTest {

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
    @DisplayName("Transaction commits all saves atomically on success")
    void testTransactionCommits() {
        shell.transaction(tx -> {
            tx.save("w1", new Wallet("Alice", 1000));
            tx.save("w2", new Wallet("Bob", 500));
        });

        Core<Wallet> core = shell.core(Wallet.class);
        assertNotNull(core.extract("w1").sync().orElse(null));
        assertNotNull(core.extract("w2").sync().orElse(null));
    }

    @Test
    @DisplayName("Transaction rolls back all changes when an exception is thrown")
    void testTransactionRollbackOnException() {
        assertThrows(DatabaseException.class, () -> shell.transaction(tx -> {
            tx.save("w3", new Wallet("Charlie", 500));
            // Simulate failure mid-transaction
            throw new RuntimeException("Simulated failure!");
        }));

        // The save of "w3" should have been rolled back
        Core<Wallet> core = shell.core(Wallet.class);
        assertTrue(core.extract("w3").sync().isEmpty(),
                "Entity should not exist after rollback");
    }

    @Test
    @DisplayName("Transaction.load() can read entities saved earlier in the same transaction")
    void testTransactionLoad() {
        shell.transaction(tx -> {
            tx.save("w4", new Wallet("Dave", 750));
            Wallet loaded = tx.load("w4", Wallet.class);
            assertNotNull(loaded, "Should be able to load within same transaction");
            assertEquals("Dave", loaded.owner);
        });
    }

    @Test
    @DisplayName("Transaction.delete() removes entity atomically")
    void testTransactionDelete() {
        Core<Wallet> core = shell.core(Wallet.class);
        core.preserve("w5", new Wallet("Eve", 200));

        shell.transaction(tx -> tx.delete("w5", Wallet.class));

        assertTrue(core.extract("w5").sync().isEmpty(), "Entity should be deleted by transaction");
    }

    // --- Entity ---
    static class Wallet {
        public String id;
        public String owner;
        public int balance;
        public Wallet() {}
        public Wallet(String owner, int balance) { this.owner = owner; this.balance = balance; }
    }

    static java.io.File createTempDb() {
        try {
            java.io.File f = java.io.File.createTempFile("caskara-test-", ".db");
            f.deleteOnExit();
            return f;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
