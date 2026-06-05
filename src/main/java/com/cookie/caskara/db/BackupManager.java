package com.cookie.caskara.db;

import java.io.File;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Manages automatic backups for Caskara Shells.
 */
public class BackupManager {
    private final Shell shell;
    private final File backupFolder;

    public BackupManager(Shell shell, File backupFolder) {
        this.shell = shell;
        this.backupFolder = backupFolder;
        if (!backupFolder.exists()) {
            backupFolder.mkdirs();
        }
    }

    /**
     * Safely performs a backup by locking the shell and copying the file.
     */
    public void performBackup() {
        shell.runInLock(() -> {
            File source = shell.getFile();
            String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File destination = new File(backupFolder, source.getName() + "." + timestamp + ".bak");
            try (Statement stmt = shell.getConnection().createStatement()) {
                stmt.executeUpdate("backup to '" + destination.getAbsolutePath() + "'");
                System.out.println("[Caskara] Native Backup created safely: " + destination.getName());
            } catch (Exception e) {
                System.err.println("[Caskara] Failed to create native backup: " + e.getMessage());
            }
            return null;
        });
    }
}
