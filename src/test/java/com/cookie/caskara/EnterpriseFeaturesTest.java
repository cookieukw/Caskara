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
}
