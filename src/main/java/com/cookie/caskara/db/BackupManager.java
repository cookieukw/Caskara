package com.cookie.caskara.db;

import com.cookie.caskara.utils.CaskaraLogger;

import java.io.File;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Manages automatic backups for Caskara Shells.
 */
public class BackupManager {

    /** How many backups to keep per shell. Auto-backup defaults to hourly, so this is ~2 days. */
    public static final int DEFAULT_RETENTION = 48;

    private final Shell shell;
    private final File backupFolder;
    private final int retention;

    public BackupManager(Shell shell, File backupFolder) {
        this(shell, backupFolder, DEFAULT_RETENTION);
    }

    /**
     * @param retention how many backups of this shell to keep; 0 or less disables pruning
     */
    public BackupManager(Shell shell, File backupFolder, int retention) {
        this.shell = shell;
        this.backupFolder = backupFolder;
        this.retention = retention;
        if (!backupFolder.exists()) {
            backupFolder.mkdirs();
        }
    }

    /**
     * Safely performs a backup by locking the shell and copying the file,
     * then prunes older backups down to the retention limit.
     */
    public void performBackup() {
        shell.runInLock(() -> {
            File source = shell.getFile();
            String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File destination = new File(backupFolder, source.getName() + "." + timestamp + ".bak");
            try (Statement stmt = shell.getConnection().createStatement()) {
                // Single quotes in the path would terminate the SQL string literal early;
                // SQLite escapes them by doubling.
                String escapedPath = destination.getAbsolutePath().replace("'", "''");
                stmt.executeUpdate("backup to '" + escapedPath + "'");
                CaskaraLogger.info("Native backup created safely: " + destination.getName());
            } catch (Exception e) {
                CaskaraLogger.error("Failed to create native backup for " + source.getName(), e);
                return null;
            }
            pruneOldBackups(source.getName());
            return null;
        });
    }

    /**
     * Deletes the oldest backups of this shell beyond the retention limit.
     * <p>
     * Without this, the default hourly auto-backup grows the folder by 24 files per shell
     * per day, forever — the repository already carried 11 stale ones.
     */
    private void pruneOldBackups(String shellFileName) {
        if (retention <= 0) {
            return;
        }
        File[] all = backupFolder.listFiles((dir, name) ->
                name.startsWith(shellFileName + ".") && name.endsWith(".bak"));
        if (all == null || all.length <= retention) {
            return;
        }

        // Names embed a sortable yyyyMMdd_HHmmss stamp, so lexicographic order is
        // chronological; lastModified is used only to break ties.
        List<File> sorted = new ArrayList<>(Arrays.asList(all));
        sorted.sort(Comparator.comparing(File::getName).thenComparingLong(File::lastModified));

        int toDelete = sorted.size() - retention;
        for (int i = 0; i < toDelete; i++) {
            File old = sorted.get(i);
            if (!old.delete()) {
                CaskaraLogger.warn("Could not prune old backup: " + old.getName());
            }
        }
        CaskaraLogger.info("Pruned " + toDelete + " old backup(s) of " + shellFileName
                + " (keeping the most recent " + retention + ").");
    }
}
