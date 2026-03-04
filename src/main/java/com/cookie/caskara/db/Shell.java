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

/**
 * A 'Shell' represents a database file/connection.
 * It manages the lifecycle of the SQLite connection and provides access to 'Cores'.
 */
public class Shell {
    private final File shellFile;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private Connection connection;
    private final Map<Class<?>, Core<?>> cores = new HashMap<>();

    public Shell(File shellFile) {
        this.shellFile = shellFile;
        initConnection();
    }

    private void initConnection() {
        try {
            Class.forName("org.sqlite.JDBC");
            shellFile.getParentFile().mkdirs();
            String url = "jdbc:sqlite:" + shellFile.getAbsolutePath();
            this.connection = DriverManager.getConnection(url);
            
            // Set some performance settings
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("PRAGMA synchronous = NORMAL");
                
                // Root table for Caskara
                stmt.execute("CREATE TABLE IF NOT EXISTS elements (" +
                        "id TEXT PRIMARY KEY," +
                        "type TEXT," +
                        "json TEXT" +
                        ")");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_type ON elements(type)");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                initConnection();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return connection;
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
