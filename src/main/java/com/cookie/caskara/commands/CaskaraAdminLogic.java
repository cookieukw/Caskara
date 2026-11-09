package com.cookie.caskara.commands;

import com.cookie.caskara.Caskara;
import com.cookie.caskara.db.BackupManager;
import com.cookie.caskara.db.Shell;
import com.cookie.caskara.utils.CaskaraLogger;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class CaskaraAdminLogic {

    public static List<String> getStats() {
        List<String> output = new ArrayList<>();
        long totalQueries = 0;
        long totalHits = 0;
        long totalMisses = 0;
        int shellCount = Caskara.getShells().size();

        for (Shell shell : Caskara.getShells().values()) {
            totalQueries += shell.getStats().getTotalQueries();
            totalHits += shell.getStats().getCacheHits();
            totalMisses += shell.getStats().getCacheMisses();
        }

        String msg = String.format("Caskara Stats: %d active shells | %d queries | Cache: %d hits, %d misses",
                shellCount, totalQueries, totalHits, totalMisses);
        output.add(msg);
        return output;
    }

    public static List<String> runVacuum() {
        List<String> output = new ArrayList<>();
        long start = System.currentTimeMillis();
        int count = 0;

        for (Shell shell : Caskara.getShells().values()) {
            try {
                // Under the shell lock: VACUUM rewrites the whole file and must not
                // race with concurrent writers.
                shell.runInLock(() -> {
                    try (Statement stmt = shell.getConnection().createStatement()) {
                        stmt.execute("VACUUM");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });
                count++;
            } catch (Exception e) {
                output.add("[Error] Failed to vacuum a shell: " + e.getMessage());
            }
        }

        long took = System.currentTimeMillis() - start;
        output.add("VACUUM completed on " + count + " databases in " + took + "ms.");
        return output;
    }

    public static List<String> dumpEntity(String id) {
        List<String> output = new ArrayList<>();
        if (id == null) {
            output.add("Usage: /caskara dump <entity_id>");
            return output;
        }

        boolean found = false;
        for (Shell shell : Caskara.getShells().values()) {
            try {
                Connection conn = shell.getConnection();
                // One id can now map to several rows (one per entity type), so dump them all.
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT type, json FROM elements WHERE id = ? ORDER BY type")) {
                    pstmt.setString(1, id);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            String type = rs.getString("type");
                            String json = rs.getString("json");

                            System.out.println("[CaskaraDump] === DUMP FOR ID: " + id + " (type: " + type + ") ===");
                            System.out.println("[CaskaraDump] JSON: " + json);
                            System.out.println("[CaskaraDump] ===========================");

                            // Keep "Dumped entity <id> to Server Console!" as a contiguous
                            // prefix — the type is appended so callers matching on the
                            // original message keep working.
                            output.add("Dumped entity " + id + " to Server Console! (type: " + type + ")");
                            found = true;
                        }
                    }
                }
                if (found) break;
            } catch (Exception e) {
                output.add("[Error] Error during dump search: " + e.getMessage());
            }
        }

        if (!found) {
            output.add("Entity ID '" + id + "' not found in any database.");
        }
        return output;
    }

    public static List<String> scanPackage(String pkg) {
        List<String> output = new ArrayList<>();
        if (pkg == null) {
            output.add("Usage: /caskara scan <package.name>");
            return output;
        }

        output.add("Scanning package " + pkg + " for CaskaraEntities...");
        try {
            Caskara.scanPackage(pkg);
            output.add("Scan complete.");
        } catch (Exception e) {
            output.add("Scan failed! See console.");
            System.err.println("[Caskara] Scan failed: " + e.getMessage());
        }
        return output;
    }

    public static List<String> runBackup() {
        List<String> output = new ArrayList<>();
        long start = System.currentTimeMillis();
        int count = 0;

        for (Shell shell : Caskara.getShells().values()) {
            try {
                // Instantiates a temporary BackupManager pointing to a 'backups' folder alongside the shell
                File backupFolder = new File(shell.getFile().getParentFile(), "backups");
                BackupManager bm = new BackupManager(shell, backupFolder);
                bm.performBackup();
                count++;
            } catch (Exception e) {
                output.add("[Error] Failed to backup a shell: " + e.getMessage());
            }
        }

        long took = System.currentTimeMillis() - start;
        output.add("Global Backup completed on " + count + " databases in " + took + "ms.");
        return output;
    }

    public static List<String> toggleAutoBackup(String hoursStr) {
        List<String> output = new ArrayList<>();
        if (hoursStr == null || hoursStr.isEmpty()) {
            output.add("Usage: /caskara autobackup <hours> (0 to disable)");
            return output;
        }

        try {
            long hours = Long.parseLong(hoursStr);
            Caskara.enableAutoBackup(hours);
            if (hours > 0) {
                output.add("Auto-Backup is now scheduled to run every " + hours + " hour(s).");
            } else {
                output.add("Auto-Backup has been DISABLED.");
            }
        } catch (NumberFormatException e) {
            output.add("Invalid hours format: " + hoursStr);
        }
        return output;
    }
}
