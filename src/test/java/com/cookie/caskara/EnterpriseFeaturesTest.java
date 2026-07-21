package com.cookie.caskara;

import com.cookie.caskara.annotations.Cache;
import com.cookie.caskara.annotations.Id;
import com.cookie.caskara.db.Core;
import com.cookie.caskara.db.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class EnterpriseFeaturesTest {

    private File testFolder;

    @BeforeEach
    void setUp() {
        testFolder = new File("test_data");
        testFolder.mkdirs();
    }

    @AfterEach
    void tearDown() {
        Caskara.shutdown();
        deleteFolder(testFolder);
    }

    private void deleteFolder(File folder) {
        if (folder.exists()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) deleteFolder(f);
                    else f.delete();
                }
            }
            folder.delete();
        }
    }

    @Cache(maxSize = 10)
    static class TinyCacheEntity {
        @Id
        String id;
        String data;
        
        TinyCacheEntity(String id, String data) {
            this.id = id;
            this.data = data;
        }
    }

    @Test
    void testDynamicCacheSize() {
        Caskara.init("test_mod", testFolder);
        Core<TinyCacheEntity> core = Caskara.core(TinyCacheEntity.class);
        
        // Push 15 items into a cache of size 10
        for (int i = 0; i < 15; i++) {
            core.preserve(new TinyCacheEntity("id_" + i, "data"));
        }
        
        // Cache misses should happen for early items because they were evicted
        core.extract("id_0");
        assertTrue(Caskara.stats().getCacheMisses() > 0, "Should have missed cache due to eviction");
    }

    @Test
    void testAsyncWriteQueuePerformance() throws InterruptedException {
        Caskara.init("test_mod", testFolder);
        Core<TinyCacheEntity> core = Caskara.core(TinyCacheEntity.class);
        
        int count = 1000;
        CountDownLatch latch = new CountDownLatch(count);
        
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            core.preserveAsync("async_" + i, new TinyCacheEntity("async_" + i, "val")).thenAccept(id -> latch.countDown());
        }
        
        // Wait for all async writes to flush to disk
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Async writes took too long");
        long duration = System.currentTimeMillis() - start;
        System.out.println("Async wrote " + count + " elements in " + duration + "ms");
        
        // Verify they actually exist
        assertNotNull(core.extract("async_999").sync().orElse(null));
    }

    @SuppressWarnings("deprecation")
    @Test
    void testDeprecatedInitFallback() {
        // Calling the old init
        Caskara.init(testFolder);
        Shell shell = Caskara.shell();
        
        assertTrue(shell.getConnection() != null);
        // Clean up
        Caskara.shutdown();
    }

    @Test
    void testAutoMigrationByType() throws Exception {
        // 1. Manually create a default.db with some legacy data of different types
        File legacyFile = new File(testFolder, "default.db");
        Shell legacyShell = new Shell(legacyFile);
        Core<TinyCacheEntity> legacyCore = new Core<>(legacyShell, TinyCacheEntity.class);
        legacyCore.preserve("item_1", new TinyCacheEntity("item_1", "legacy_val"));
        
        // Also put a dummy entity of another class in default.db to make sure it's NOT stolen
        // Let's use raw SQL or just another shell write for simulation
        try (var conn = legacyShell.getConnection();
             var stmt = conn.prepareStatement("INSERT INTO elements (id, type, json) VALUES ('other_id', 'other_class', '{}')")) {
            stmt.executeUpdate();
        }
        legacyShell.close();

        // 2. Initialize with new namespace "my_new_mod"
        Caskara.init("my_new_mod", testFolder);
        Core<TinyCacheEntity> newCore = Caskara.core(TinyCacheEntity.class);

        // 3. Verify that the entity "item_1" was successfully migrated to the new database
        assertTrue(newCore.extract("item_1").sync().isPresent(), "Legacy data should be migrated");
        assertEquals("legacy_val", newCore.extract("item_1").sync().get().data);

        // 4. Verify that default.db.migration.bak exists
        File backupFile = new File(testFolder, "default.db.migration.bak");
        assertTrue(backupFile.exists(), "Backup of default.db should exist");

        // 5. Verify that 'tinycacheentity' is DELETED from default.db, but 'other_class' remains
        Shell checkLegacyShell = new Shell(legacyFile);
        try (var conn = checkLegacyShell.getConnection();
             var stmt = conn.prepareStatement("SELECT count(*) FROM elements WHERE type = 'tinycacheentity'")) {
            var rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Legacy type should be deleted from default.db");
        }
        try (var conn = checkLegacyShell.getConnection();
             var stmt = conn.prepareStatement("SELECT count(*) FROM elements WHERE type = 'other_class'")) {
            var rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Other mod's class should remain in default.db");
        }
        checkLegacyShell.close();
    }
}
