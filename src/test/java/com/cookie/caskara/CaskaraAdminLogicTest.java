package com.cookie.caskara;

import com.cookie.caskara.annotations.CaskaraEntity;
import com.cookie.caskara.commands.CaskaraAdminLogic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CaskaraAdminLogicTest {

    private static final File testDir = new File("test_admin_logic_db");

    @CaskaraEntity
    public static class AdminDummy {
        public String id;
        public String data;

        public AdminDummy() {}
        public AdminDummy(String id, String data) {
            this.id = id;
            this.data = data;
        }
    }

    private static String savedId;

    @BeforeAll
    public static void setup() {
        Caskara.init(testDir);
        savedId = Caskara.save(new AdminDummy("dummy123", "secret_data"), 5000);
    }

    @AfterAll
    public static void cleanup() {
        for (File f : testDir.listFiles()) {
            f.delete();
        }
        testDir.delete();
    }

    @Test
    public void testGetStats() {
        List<String> stats = CaskaraAdminLogic.getStats();
        assertEquals(1, stats.size());
        assertTrue(stats.get(0).contains("Caskara Stats"));
        assertTrue(stats.get(0).contains("active shells"));
    }

    @Test
    public void testRunVacuum() {
        List<String> vacuumResult = CaskaraAdminLogic.runVacuum();
        assertEquals(1, vacuumResult.size());
        assertTrue(vacuumResult.get(0).contains("VACUUM completed"));
    }

    @Test
    public void testRunBackup() {
        List<String> backupResult = CaskaraAdminLogic.runBackup();
        assertEquals(1, backupResult.size());
        assertTrue(backupResult.get(0).contains("Global Backup completed"));

        // Verify the file was created
        File backupDir = new File(new File(testDir, "global"), "backups");
        assertTrue(backupDir.exists(), "Backup directory should be created");
        File[] bakFiles = backupDir.listFiles((dir, name) -> name.endsWith(".bak"));
        assertTrue(bakFiles != null && bakFiles.length > 0, "There should be at least one .bak file");
    }

    @Test
    public void testDumpEntityExists() {
        List<String> dumpResult = CaskaraAdminLogic.dumpEntity(savedId);
        assertEquals(1, dumpResult.size());
        assertTrue(dumpResult.get(0).contains("Dumped entity " + savedId + " to Server Console!"));
    }

    @Test
    public void testDumpEntityNotFound() {
        List<String> dumpResult = CaskaraAdminLogic.dumpEntity("not_found_id");
        assertEquals(1, dumpResult.size());
        assertTrue(dumpResult.get(0).contains("not found in any database"));
    }

    @Test
    public void testDumpEntityNull() {
        List<String> dumpResult = CaskaraAdminLogic.dumpEntity(null);
        assertEquals(1, dumpResult.size());
        assertTrue(dumpResult.get(0).contains("Usage: /caskara dump"));
    }

    @Test
    public void testScanPackage() {
        List<String> scanResult = CaskaraAdminLogic.scanPackage("com.cookie.caskara");
        assertEquals(2, scanResult.size()); // Should output "Scanning..." and "Scan complete."
        assertTrue(scanResult.get(0).contains("Scanning package"));
        assertTrue(scanResult.get(1).contains("Scan complete"));
    }
}
