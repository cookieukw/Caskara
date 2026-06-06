package com.cookie.caskara;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageScannerTest {
    private File tempDir;

    @BeforeEach
    void setup() {
        tempDir = new File(System.getProperty("java.io.tmpdir"), "caskara-scan-test-" + System.currentTimeMillis());
        tempDir.mkdirs();
        Caskara.init(tempDir);
    }

    @AfterEach
    void teardown() {
        Caskara.shutdown();
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            tempDir.delete();
        }
    }

    @Test
    @DisplayName("scanPackage() automatically registers entities annotated with @CaskaraEntity")
    void testPackageScanningRegistersEntities() {
        File expectedDb = new File(new File(tempDir, "global"), "scanned_dummy.db");
        
        // Ensure DB does not exist before scan
        assertFalse(expectedDb.exists(), "DB should not exist before scan");

        // Perform the scan on our test sub-package
        Caskara.scanPackage("com.cookie.caskara.scanner");

        // The auto-scan should have found ScannerDummyEntity and called Caskara.register(),
        // which instantiates the Core and creates the SQL file "scanned_dummy.db".
        assertTrue(expectedDb.exists(), "DB should be created automatically during registration via scan");
    }
}
