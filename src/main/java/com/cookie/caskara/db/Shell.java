package com.cookie.caskara.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import com.cookie.caskara.exceptions.DatabaseException;

/**
 * A 'Shell' represents a database file/connection.
 * It manages the lifecycle of the SQLite connection and provides access to 'Cores'.
 * Thread-safe via ReentrantLock.
 */
public class Shell {
    private final File shellFile;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantLock lock = new ReentrantLock();
    private Connection connection;
    private final Map<Class<?>, Core<?>> cores = new HashMap<>();
    private final Stats stats = new Stats();

    public Shell(File shellFile) {
        this.shellFile = shellFile;
        initConnection();
    }

    public Stats getStats() {
        return stats;
    }

    private void initConnection() {
        lock.lock();
        try {
            Class.forName("org.sqlite.JDBC");
            if (shellFile.getParentFile() != null) {
                shellFile.getParentFile().mkdirs();
            }
            String url = "jdbc:sqlite:" + shellFile.getAbsolutePath();
            this.connection = DriverManager.getConnection(url);
            
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("PRAGMA synchronous = NORMAL");
                
                stmt.execute("CREATE TABLE IF NOT EXISTS elements (" +
                        "id TEXT PRIMARY KEY," +
                        "type TEXT," +
                        "json TEXT," +
                        "expires_at INTEGER," +
                        "deleted_at INTEGER," +
                        "version INTEGER DEFAULT 1" + // Schema migration support
                        ")");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_type ON elements(type)");
                
                // Migrations for existing databases
                try { stmt.execute("ALTER TABLE elements ADD COLUMN expires_at INTEGER"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE elements ADD COLUMN deleted_at INTEGER"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE elements ADD COLUMN version INTEGER DEFAULT 1"); } catch (SQLException ignored) {}
            }
            
            startCleanupTask();
        } catch (Exception e) {
            throw new DatabaseException("Failed to initialize shell connection for: " + shellFile.getName(), e);
        } finally {
            lock.unlock();
        }
    }

    public Connection getConnection() {
        lock.lock();
        try {
            if (connection == null || connection.isClosed()) {
                initConnection();
            }
            return connection;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get or verify connection", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Executes an action within the shell's lock to ensure thread safety.
     */
    public <R> R runInLock(java.util.function.Supplier<R> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Executes a series of operations within a single SQL transaction.
     * Thread-safe and atomic.
     */
    public void transaction(java.util.function.Consumer<Transaction> action) {
        lock.lock();
        try {
            Connection conn = getConnection();
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                action.accept(new Transaction(this));
                conn.commit();
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    // Log or handle rollback failure
                }
                throw new DatabaseException("Transaction failed and was rolled back", e);
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to manage transaction state", e);
        } finally {
            lock.unlock();
        }
    }

    public File getFile() {
        return shellFile;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * Gets or creates a 'Core' for the specified class.
     */
    @SuppressWarnings("unchecked")
    public <T> Core<T> core(Class<T> clazz) {
        return (Core<T>) cores.computeIfAbsent(clazz, c -> new Core<>(this, (Class<T>) c));
    }

    /**
     * Exports all data in this shell to a JSON file.
     */
    public void exportToJson(File file) {
        runInLock(() -> {
            try {
                java.util.List<java.util.Map<String, String>> data = new java.util.ArrayList<>();
                try (Statement stmt = getConnection().createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM elements")) {
                    while (rs.next()) {
                        java.util.Map<String, String> row = new java.util.HashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("type", rs.getString("type"));
                        row.put("json", rs.getString("json"));
                        data.add(row);
                    }
                }
                String fullJson = Core.getGson().toJson(data);
                java.nio.file.Files.writeString(file.toPath(), fullJson);
            } catch (Exception e) {
                throw new DatabaseException("Failed to export shell to JSON", e);
            }
            return null;
        });
    }

    /**
     * Imports data from a JSON file into this shell.
     */
    public void importFromJson(File file) {
        runInLock(() -> {
            try {
                String content = java.nio.file.Files.readString(file.toPath());
                java.util.List<java.util.Map<String, String>> data = Core.getGson().fromJson(content, java.util.List.class);
                
                String sql = "INSERT OR REPLACE INTO elements (id, type, json) VALUES (?, ?, ?)";
                try (java.sql.PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                    for (java.util.Map<String, String> row : data) {
                        pstmt.setString(1, row.get("id"));
                        pstmt.setString(2, row.get("type"));
                        pstmt.setString(3, row.get("json"));
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }
            } catch (Exception e) {
                throw new DatabaseException("Failed to import shell from JSON", e);
            }
            return null;
        });
    }

    private void startCleanupTask() {
        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            runInLock(() -> {
                try (PreparedStatement pstmt = getConnection().prepareStatement(
                        "DELETE FROM elements WHERE expires_at IS NOT NULL AND expires_at < ?")) {
                    pstmt.setLong(1, System.currentTimeMillis());
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    System.err.println("[Caskara] Failed to clean up expired records: " + e.getMessage());
                }
                return null;
            });
        }, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
    }

    /**
     * Closes the shell and its connections.
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
            executor.shutdown();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
