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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public static Map<String, String> getGlobalStatsMap() {
        long totalHits = 0;
        long totalMisses = 0;
        long totalMemoryBytes = 0;
        long totalEntities = 0;

        for (Shell shell : Caskara.getShells().values()) {
            totalHits += shell.getStats().getCacheHits();
            totalMisses += shell.getStats().getCacheMisses();
            if (shell.getFile() != null && shell.getFile().exists()) {
                totalMemoryBytes += shell.getFile().length();
            }
            try {
                try (Statement stmt = shell.getConnection().createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT count(*) FROM elements")) {
                    if (rs.next()) {
                        totalEntities += rs.getLong(1);
                    }
                }
            } catch (Exception ignored) {}
        }

        long totalAccesses = totalHits + totalMisses;
        double hitRate = totalAccesses > 0 ? ((double) totalHits / totalAccesses) * 100 : 0.0;
        double memMB = totalMemoryBytes / 1024.0 / 1024.0;

        Map<String, String> stats = new HashMap<>();
        stats.put("HitRate", String.format("%.1f%%", hitRate));
        stats.put("Memory", String.format("%.1f MB", memMB));
        stats.put("Total", String.valueOf(totalEntities));
        return stats;
    }

    public static class EntityData {
        public String id;
        public String type;
        public String size;
        public String ttl;
    }

    public static List<EntityData> getShellEntities(String shellName, int offset, int limit) {
        List<EntityData> list = new ArrayList<>();
        Shell targetShell = null;
        System.out.println("[CaskaraAdmin] Requested shell: " + shellName);
        for (Shell s : Caskara.getShells().values()) {
            if (s.getFile() != null) {
                System.out.println("[CaskaraAdmin] Checking shell file: " + s.getFile().getName());
                if (s.getFile().getName().equals(shellName)) {
                    targetShell = s;
                    break;
                }
            }
        }
        if (targetShell == null) {
            System.out.println("[CaskaraAdmin] targetShell is NULL for " + shellName);
            return list;
        }

        try {
            String sql = "SELECT id, type, length(json) as sizeBytes, expires_at FROM elements LIMIT ? OFFSET ?";
            try (PreparedStatement pstmt = targetShell.getConnection().prepareStatement(sql)) {
                pstmt.setInt(1, limit);
                pstmt.setInt(2, offset);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        EntityData data = new EntityData();
                        data.id = rs.getString("id");
                        data.type = rs.getString("type");
                        if (data.type == null) data.type = "Unknown";
                        if (data.id == null) data.id = "null";
                        
                        long bytes = rs.getLong("sizeBytes");
                        data.size = String.format("%.1f KB", bytes / 1024.0);
                        
                        long expires = rs.getLong("expires_at");
                        if (expires == 0 || rs.wasNull()) {
                            data.ttl = "Permanent";
                        } else {
                            long diff = expires - System.currentTimeMillis();
                            if (diff <= 0) data.ttl = "Expired";
                            else data.ttl = (diff / 60000) + " mins";
                        }
                        list.add(data);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[CaskaraAdmin] SQL Error in getShellEntities:");
            e.printStackTrace();
        }
        System.out.println("[CaskaraAdmin] Returning " + list.size() + " entities.");
        return list;
    }

    public static boolean deleteEntity(String shellName, String id) {
        Shell targetShell = null;
        for (Shell s : Caskara.getShells().values()) {
            if (s.getFile() != null && s.getFile().getName().equals(shellName)) {
                targetShell = s;
                break;
            }
        }
        if (targetShell == null) return false;
        final Shell shell = targetShell;
        // Runs under the shell lock so it cannot race with writers, and invalidates the
        // Core LRU caches afterwards — otherwise extract() would keep serving the
        // deleted entity from memory.
        return shell.runInLock(() -> {
            try (PreparedStatement pstmt = shell.getConnection().prepareStatement("DELETE FROM elements WHERE id = ?")) {
                pstmt.setString(1, id);
                boolean deleted = pstmt.executeUpdate() > 0;
                if (deleted) {
                    shell.invalidateCaches();
                }
                return deleted;
            } catch (Exception e) {
                CaskaraLogger.error("Failed to delete entity " + id + " from " + shellName, e);
                return false;
            }
        });
    }

    public static List<String> runVacuum() {
        List<String> output = new ArrayList<>();
        long start = System.currentTimeMillis();
        int count = 0;

        for (Shell shell : Caskara.getShells().values()) {
            try {
                Connection conn = shell.getConnection();
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("VACUUM");
                }
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
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT type, json FROM elements WHERE id = ?")) {
                    pstmt.setString(1, id);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            String type = rs.getString("type");
                            String json = rs.getString("json");

                            System.out.println("[CaskaraDump] === DUMP FOR ID: " + id + " ===");
                            System.out.println("[CaskaraDump] Type: " + type);
                            System.out.println("[CaskaraDump] JSON: " + json);
                            System.out.println("[CaskaraDump] ===========================");

                            output.add("Dumped entity " + id + " to Server Console!");
                            found = true;
                            break;
                        }
                    }
                }
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
