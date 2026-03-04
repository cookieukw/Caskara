package com.cookie.caskara.db;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages automatic backups for Caskara Shells.
 */
public class BackupManager {
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
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
     * Starts automatic backups with a specific interval.
     */
    public void startAutoBackup(int intervalMinutes) {
        scheduler.scheduleAtFixedRate(this::performBackup, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    /**
     * Safely performs a backup by locking the shell and copying the file.
     */
    public void performBackup() {
        shell.runInLock(() -> {
            File source = shell.getFile();
            File destination = new File(backupFolder, source.getName() + ".bak");
            try {
                Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Caskara] Backup created for: " + source.getName());
            } catch (IOException e) {
                System.err.println("[Caskara] Failed to create backup: " + e.getMessage());
            }
            return null;
        });
    }
}
