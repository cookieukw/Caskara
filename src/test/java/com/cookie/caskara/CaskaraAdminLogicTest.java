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

    @BeforeAll
    public static void setup() {
        Caskara.init(testDir);
        Caskara.save(new AdminDummy("dummy123", "secret_data"), 5000);
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
    public void testDumpEntityExists() {
        List<String> dumpResult = CaskaraAdminLogic.dumpEntity("dummy123");
        assertEquals(1, dumpResult.size());
        assertTrue(dumpResult.get(0).contains("Dumped entity dummy123 to Server Console!"));
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
