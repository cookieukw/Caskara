package com.cookie.caskara.db;

import com.cookie.caskara.exceptions.DatabaseException;
import com.cookie.caskara.utils.CaskaraLogger;
import com.google.gson.Gson;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.nio.file.Files;


/**
 * A 'Shell' represents a database file/connection.
 * It manages the lifecycle of the SQLite connection and provides access to 'Cores'.
 * Thread-safe via ReentrantLock.
 */
public class Shell {
    private static final Gson GSON = new Gson();
    private final File shellFile;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Connection connection;
    private final Map<Class<?>, Core<?>> cores = new ConcurrentHashMap<>();
    private final Object coreCreationLock = new Object();
    private final Stats stats = new Stats();
    private ScheduledExecutorService cleanupScheduler;

    /** Nesting depth of {@link #transaction(Consumer)} on the thread currently holding the lock. */
    private int transactionDepth = 0;
    
    private final BlockingQueue<Runnable> asyncWriteQueue = new LinkedBlockingQueue<>();
    private Thread asyncWriterThread;

    public Shell(File shellFile) {
        this.shellFile = shellFile;
        initConnection();
    }

    public File getShellFile() {
        return shellFile;
    }

    public Stats getStats() {
        return stats;
    }

    private void initConnection() {
        lock.lock();
        try {
            if (connection != null && !connection.isClosed()) {
                return;
            }
            Class.forName("org.sqlite.JDBC");
            if (shellFile.getParentFile() != null) {
                shellFile.getParentFile().mkdirs();
            }
            String url = "jdbc:sqlite:" + shellFile.getAbsolutePath();
            this.connection = DriverManager.getConnection(url);
            
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("PRAGMA synchronous = NORMAL");
                // Required so that "INSERT OR REPLACE" fires the AFTER DELETE triggers that
                // keep the FTS5 index in sync. Without it the index accumulates stale rows.
                stmt.execute("PRAGMA recursive_triggers = ON");
                
                // Fresh databases get the composite key straight away. Existing ones are
                // upgraded by upgradeToCompositeKey() below.
                stmt.execute("CREATE TABLE IF NOT EXISTS elements (" +
                        "id TEXT NOT NULL," +
                        "type TEXT NOT NULL," +
                        "json TEXT," +
                        "expires_at INTEGER," +
                        "deleted_at INTEGER," +
                        "version INTEGER DEFAULT 1," + // Schema migration support
                        "PRIMARY KEY (id, type)" +
                        ")");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_type ON elements(type)");

            // Migrations for existing databases
                try { stmt.execute("ALTER TABLE elements ADD COLUMN expires_at INTEGER"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE elements ADD COLUMN deleted_at INTEGER"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE elements ADD COLUMN version INTEGER DEFAULT 1"); } catch (SQLException ignored) {}
            }

            upgradeToCompositeKey();
            
            if (asyncWriterThread == null) {
                asyncWriterThread = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Runnable firstTask = asyncWriteQueue.take();
                            transaction(tx -> {
                                firstTask.run();
                                Runnable nextTask;
                                while ((nextTask = asyncWriteQueue.poll()) != null) {
                                    nextTask.run();
                                }
                            });
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception e) {
                            System.err.println("[Caskara] Async writer thread encountered an error: " + e.getMessage());
                        }
                    }
                }, "Caskara-Writer-" + shellFile.getName());
                asyncWriterThread.setDaemon(true);
                asyncWriterThread.start();
            }

            startCleanupTask();
        } catch (Exception e) {
            throw new DatabaseException("Failed to initialize shell connection for: " + shellFile.getName(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Caskara's own on-disk schema version, tracked via {@code PRAGMA user_version}.
     * <p>
     * 0 = legacy layout, {@code id TEXT PRIMARY KEY}<br>
     * 1 = {@code PRIMARY KEY (id, type)}
     */
    private static final int SCHEMA_VERSION_COMPOSITE_KEY = 1;

    /**
     * Rebuilds the {@code elements} table with a composite primary key.
     * <p>
     * The original schema declared {@code id TEXT PRIMARY KEY} while keeping {@code type}
     * as an ordinary column. Since every write is an {@code INSERT OR REPLACE}, saving two
     * different entity types under the same id (a player name or UUID, say) silently
     * destroyed the first record: the row was replaced, and every read filters on
     * {@code id AND type}, so the old entity simply disappeared.
     * <p>
     * Runs once per database file, inside a transaction, after taking a consistent
     * snapshot of the file. If anything fails the transaction is rolled back and the
     * database is left exactly as it was.
     * <p>
     * Note: indexes created at runtime through {@link Core#createIndex(String)} live on the
     * old table and are dropped with it. Indexes declared via {@code @Index} are recreated
     * automatically the next time the Core is built; purely programmatic ones must be
     * re-issued by the caller.
     */
    private void upgradeToCompositeKey() throws SQLException {
        Connection conn = connection;

        int userVersion;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            userVersion = rs.next() ? rs.getInt(1) : 0;
        }
        if (userVersion >= SCHEMA_VERSION_COMPOSITE_KEY) {
            return;
        }

        // A freshly created file already has the right shape — just stamp the version.
        if (hasCompositePrimaryKey(conn)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA user_version = " + SCHEMA_VERSION_COMPOSITE_KEY);
            }
            return;
        }

        CaskaraLogger.warn("Caskara: upgrading '" + shellFile.getName()
                + "' to a composite primary key (id, type). Taking a snapshot first...");

        File snapshot = new File(shellFile.getParentFile(), shellFile.getName() + ".pre-composite-key.bak");
        try (Statement stmt = conn.createStatement()) {
            // VACUUM INTO writes a consistent copy without needing the file to be closed.
            if (snapshot.exists() && !snapshot.delete()) {
                throw new DatabaseException("Cannot overwrite stale migration snapshot: " + snapshot.getAbsolutePath());
            }
            stmt.execute("VACUUM INTO '" + snapshot.getAbsolutePath().replace("'", "''") + "'");
        } catch (SQLException e) {
            throw new DatabaseException("Aborting composite-key upgrade for " + shellFile.getName()
                    + ": could not create a safety snapshot. The database was not modified.", e);
        }

        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            // The FTS triggers belong to the old table and would be dropped with it; the
            // shadow index would then hold rows keyed by rowids that no longer exist.
            // Drop both so Core.initializeFts() rebuilds them cleanly on next use.
            dropFtsArtifacts(stmt);

            stmt.execute("CREATE TABLE elements_migrated (" +
                    "id TEXT NOT NULL," +
                    "type TEXT NOT NULL," +
                    "json TEXT," +
                    "expires_at INTEGER," +
                    "deleted_at INTEGER," +
                    "version INTEGER DEFAULT 1," +
                    "PRIMARY KEY (id, type)" +
                    ")");

            // The old PK guaranteed ids were unique, so no (id, type) pair can collide here.
            // Rows with a NULL type predate the type column and would violate NOT NULL.
            stmt.execute("INSERT INTO elements_migrated (id, type, json, expires_at, deleted_at, version) " +
                    "SELECT id, COALESCE(type, ''), json, expires_at, deleted_at, COALESCE(version, 1) " +
                    "FROM elements WHERE id IS NOT NULL");

            stmt.execute("DROP TABLE elements");
            stmt.execute("ALTER TABLE elements_migrated RENAME TO elements");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_type ON elements(type)");
            stmt.execute("PRAGMA user_version = " + SCHEMA_VERSION_COMPOSITE_KEY);

            conn.commit();
            CaskaraLogger.info("Caskara: '" + shellFile.getName()
                    + "' upgraded to composite primary key. Snapshot kept at " + snapshot.getName());
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                CaskaraLogger.error("Rollback of the composite-key upgrade failed", rollbackEx);
            }
            throw new DatabaseException("Composite-key upgrade failed for " + shellFile.getName()
                    + ". The database was rolled back; a snapshot is available at " + snapshot.getAbsolutePath(), e);
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /** True when {@code elements} already declares both id and type as primary key columns. */
    private boolean hasCompositePrimaryKey(Connection conn) throws SQLException {
        int pkColumns = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(elements)")) {
            while (rs.next()) {
                if (rs.getInt("pk") > 0) {
                    pkColumns++;
                }
            }
        }
        return pkColumns >= 2;
    }

    /** Removes the FTS5 shadow table and every per-type trigger attached to {@code elements}. */
    private void dropFtsArtifacts(Statement stmt) throws SQLException {
        List<String> triggers = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(
                // '_' is a LIKE wildcard, so it must be escaped to match the literal
                // trigger names Core.initializeFts() creates (fts_ai_/fts_ad_/fts_au_).
                "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'fts\\_a%' ESCAPE '\\'")) {
            while (rs.next()) {
                triggers.add(rs.getString("name"));
            }
        }
        for (String trigger : triggers) {
            stmt.execute("DROP TRIGGER IF EXISTS \"" + trigger.replace("\"", "\"\"") + "\"");
        }
        stmt.execute("DROP TABLE IF EXISTS fts_elements");
    }

    public Connection getConnection() {
        // ReentrantLock is reentrant — safe to call from locked contexts.
        // We avoid calling lock.lock() here again to keep the logic simple.
        // Connection validity is checked lazily.
        if (connection == null) {
            initConnection();
        }
        try {
            if (connection.isClosed()) {
                initConnection();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to verify connection state", e);
        }
        return connection;
    }

    /**
     * Enqueues a write operation to be processed by the background batch writer thread.
     */
    public void enqueueWrite(Runnable task) {
        asyncWriteQueue.offer(task);
    }

    /**
     * Executes an action within the shell's lock to ensure thread safety.
     */
    public <R> R runInLock(Supplier<R> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * True when the calling thread already owns the shell lock (i.e. it is inside
     * {@link #runInLock(Supplier)} or {@link #transaction(Consumer)}).
     * Used to avoid dispatching reads to another thread, which would deadlock.
     */
    public boolean isLockHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    /**
     * Executes a series of operations within a single SQL transaction.
     * Thread-safe and atomic.
     */
    public void transaction(Consumer<Transaction> action) {
        lock.lock();
        try {
            // Nested transaction: join the outer one instead of committing early.
            // Committing here would make the outer transaction non-atomic.
            if (transactionDepth > 0) {
                transactionDepth++;
                try {
                    action.accept(new Transaction(this));
                } finally {
                    transactionDepth--;
                }
                return;
            }

            Connection conn = getConnection();
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            transactionDepth = 1;
            try {
                action.accept(new Transaction(this));
                conn.commit();
            } catch (Exception e) {
                try {
                    conn.rollback();
                    // Invalidate all Core caches — in-memory state may reflect
                    // writes that were just rolled back in the DB.
                    clearAllCaches();
                } catch (SQLException rollbackEx) {
                    System.err.println("[Caskara] Rollback failed: " + rollbackEx.getMessage());
                }
                throw new DatabaseException("Transaction failed and was rolled back", e);
            } finally {
                transactionDepth = 0;
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to manage transaction state", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Public entry point to drop every Core's in-memory cache for this shell.
     * Required after out-of-band writes (e.g. the admin UI deleting rows directly).
     */
    public void invalidateCaches() {
        clearAllCaches();
    }

    /**
     * Clears all Core caches (used after a transaction rollback to stay in sync with DB).
     */
    void clearAllCaches() {
        for (Core<?> core : cores.values()) {
            core.clearCache();
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
        // NOTE: deliberately not computeIfAbsent — the Core constructor runs DDL,
        // opens connections and may re-enter shell.core(), which would either
        // deadlock a ConcurrentHashMap bin or throw IllegalStateException
        // ("recursive update").
        Core<T> existing = (Core<T>) cores.get(clazz);
        if (existing != null) {
            return existing;
        }
        synchronized (coreCreationLock) {
            existing = (Core<T>) cores.get(clazz);
            if (existing != null) {
                return existing;
            }
            Core<T> created = new Core<>(this, clazz);
            cores.put(clazz, created);
            return created;
        }
    }

    /**
     * Exports all data in this shell to a JSON file.
     */
    public void exportToJson(File file) {
        runInLock(() -> {
            try {
                List<Map<String, String>> data = new ArrayList<>();
                try (Statement stmt = getConnection().createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM elements")) {
                    while (rs.next()) {
                        Map<String, String> row = new HashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("type", rs.getString("type"));
                        row.put("json", rs.getString("json"));
                        data.add(row);
                    }
                }
                String fullJson = GSON.toJson(data);
                Files.writeString(file.toPath(), fullJson);
            } catch (Exception e) {
                throw new DatabaseException("Failed to export shell to JSON", e);
            }
            return null;
        });
    }

    /**
     * Imports data from a JSON file into this shell.
     */
    @SuppressWarnings("unchecked")
    public void importFromJson(File file) {
        runInLock(() -> {
            try {
                String content = Files.readString(file.toPath());
                List<Map<String, String>> data = GSON.fromJson(content, List.class);

                // Insert with version=1 so that migrations can be applied on next read
                String sql = "INSERT OR REPLACE INTO elements (id, type, json, version) VALUES (?, ?, ?, 1)";
                try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                    for (Map<String, String> row : data) {
                        String id = row.get("id");
                        if (id == null) continue; // id and type are NOT NULL in the schema
                        pstmt.setString(1, id);
                        pstmt.setString(2, row.get("type") == null ? "" : row.get("type"));
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
        // initConnection() may run again after a reconnect; without this guard every
        // reconnect leaked an extra scheduler thread.
        if (cleanupScheduler != null && !cleanupScheduler.isShutdown()) {
            return;
        }
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "caskara-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(() -> {
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
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Closes the shell and its connections.
     */
    public void close() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdownNow();
        }
        if (asyncWriterThread != null) {
            asyncWriterThread.interrupt();
            asyncWriterThread = null;
        }
        executor.shutdown();
        clearAllCaches();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
