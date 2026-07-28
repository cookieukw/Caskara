package com.cookie.caskara;

import com.cookie.caskara.annotations.CaskaraEntity;
import com.cookie.caskara.commands.CaskaraAdminLogic;
import com.cookie.caskara.commands.CaskaraCommand;
import com.cookie.caskara.db.Core;
import com.cookie.caskara.db.Query;
import com.cookie.caskara.db.Shell;
import com.cookie.caskara.db.Stats;
import com.cookie.caskara.db.Transaction;
import com.cookie.caskara.util.PackageScanner;
import com.cookie.caskara.utils.CaskaraLogger;
import com.hypixel.hytale.server.core.command.system.CommandRegistry;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.File;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import com.google.gson.JsonObject;

/**
 * Caskara API - Shell and Core Paradigm.
 * Entry point for managing data shells and cores.
 */
public class Caskara {
    private static File dataFolder;
    private static final Map<String, Shell> shells = new ConcurrentHashMap<>();
    private static String defaultNamespace = "default";
    
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> autoVacuumTask;
    private static ScheduledFuture<?> autoBackupTask;

    public static Map<String, Shell> getShells() {
        return shells;
    }

    /**
     * Initializes the Caskara API with a unique namespace per mod.
     * @param modId The unique identifier of your mod (e.g. "my_awesome_mod").
     * @param folder The root folder for all shells.
     */
    public static void init(String modId, File folder) {
        defaultNamespace = modId;
        dataFolder = folder;
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        
        // Enable Auto-Vacuum by default every 12 hours
        enableAutoVacuum(12);
        
        // Enable Auto-Backup by default every 1 hour
        enableAutoBackup(1);
    }

    /**
     * Initializes the Caskara API.
     * @param folder The root folder for all shells.
     * @deprecated Use {@link #init(String, File)} instead to prevent data conflicts with other mods using "default.db".
     */
    @Deprecated
    public static void init(File folder) {
        CaskaraLogger.warn("Caskara.init(File) is deprecated! Please migrate to Caskara.init(modId, File) to avoid default.db conflicts with other mods.");
        init("default", folder);
    }

    /**
     * Enables or changes the Auto-Vacuum interval in hours.
     * If 0 or negative, cancels the current Auto-Vacuum task.
     */
    public static synchronized void enableAutoVacuum(long periodHours) {
        ensureScheduler();

        if (autoVacuumTask != null && !autoVacuumTask.isCancelled()) {
            autoVacuumTask.cancel(false);
        }
        
        if (periodHours > 0) {
            autoVacuumTask = scheduler.scheduleAtFixedRate(() -> {
                try {
                    CaskaraAdminLogic.runVacuum();
                } catch (Exception ignored) {}
            }, periodHours, periodHours, TimeUnit.HOURS);
        }
    }

    /**
     * Enables or changes the Auto-Backup interval in hours.
     * If 0 or negative, cancels the current Auto-Backup task.
     */
    public static synchronized void enableAutoBackup(long periodHours) {
        ensureScheduler();

        if (autoBackupTask != null && !autoBackupTask.isCancelled()) {
            autoBackupTask.cancel(false);
        }
        
        if (periodHours > 0) {
            autoBackupTask = scheduler.scheduleAtFixedRate(() -> {
                try {
                    CaskaraAdminLogic.runBackup();
                } catch (Exception ignored) {}
            }, periodHours, periodHours, TimeUnit.HOURS);
        }
    }

    /**
     * Safely terminates all Caskara background tasks.
     * Ideal to be called during the Hytale server shutdown process.
     */
    private static void ensureScheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Caskara-Scheduler");
                t.setDaemon(true);
                return t;
            });
        }
    }

    public static synchronized void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        autoVacuumTask = null;
        autoBackupTask = null;
        for (Shell shell : shells.values()) {
            try {
                shell.close();
            } catch (Exception e) {
                System.err.println("[Caskara] Failed to close shell: " + e.getMessage());
            }
        }
        shells.clear();
    }

    /**
     * Opens a global Shell by name.
     */
    public static Shell shell(String name) {
        requireInitialized();
        return shells.computeIfAbsent("global:" + name,
            n -> new Shell(new File(dataFolder, "global/" + name + ".db")));
    }

    /**
     * Fails fast with a readable message instead of silently writing shells to a
     * relative path when init() was never called.
     */
    private static void requireInitialized() {
        if (dataFolder == null) {
            throw new IllegalStateException(
                "Caskara has not been initialized. Call Caskara.init(modId, dataFolder) during your plugin's setup() before using the API.");
        }
    }

    /**
     * Opens the default global Shell for this mod's namespace.
     */
    public static Shell shell() {
        return shell(defaultNamespace);
    }

    /**
     * Opens a Shell dedicated to a specific world.
     */
    public static Shell shell(World world, String name) {
        requireInitialized();
        return shells.computeIfAbsent("world:" + world.getName() + ":" + name,
            n -> new Shell(new File(dataFolder, "worlds/" + world.getName() + "/" + name + ".db")));
    }

    /**
     * Quickly gets a Core. Respects the @CaskaraEntity(shell="...") annotation if present.
     * Otherwise defaults to the default global Shell.
     */
    public static <T> Core<T> core(Class<T> clazz) {
        CaskaraEntity entity = clazz.getAnnotation(CaskaraEntity.class);
        if (entity != null && !entity.shell().equals("default")) {
            return shell(entity.shell()).core(clazz);
        }
        return shell().core(clazz);
    }

    /**
     * Pre-registers a class to initialize its Core, create SQL indexes, and validate annotations early.
     */
    public static <T> void register(Class<T> clazz) {
        core(clazz); // Getting the core triggers its constructor and annotation parsing
    }

    /**
     * Returns a fluent Query builder for the given class.
     */
    public static <T>Query<T> query(Class<T> clazz) {
        return core(clazz).query();
    }

    // --- PRO STATIC API ---

    /**
     * Saves an object and returns its generated/preserved ID.
     */
    @SuppressWarnings("unchecked")
    public static <T> String save(String id, T object) {
        return core((Class<T>) object.getClass()).preserve(id, object);
    }

    /**
     * Saves an object. If it already has an ID, it updates it. Otherwise, generates an automatic UUID.
     */
    @SuppressWarnings("unchecked")
    public static <T> String save(T object) {
        return core((Class<T>) object.getClass()).preserve(getId(object), object);
    }

    /**
     * Saves an object with a TTL (Time To Live).
     */
    @SuppressWarnings("unchecked")
    public static <T> String save(T object, Duration ttl) {
        long millis = (ttl.getSeconds() * 1000L) + (ttl.getNano() / 1000000L);
        return core((Class<T>) object.getClass()).preserve(null, object, System.currentTimeMillis() + millis);
    }

    /**
     * Saves an object with an automatic UUID and a TTL expressed as a duration in
     * milliseconds (i.e. the record expires {@code ttlMillis} from now).
     */
    @SuppressWarnings("unchecked")
    public static <T> String save(T object, long ttlMillis) {
        // preserve()'s third parameter is an ABSOLUTE epoch timestamp. Passing ttlMillis
        // straight through made every record expire in 1970, silently hiding the data.
        Long expiresAt = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : null;
        return core((Class<T>) object.getClass()).preserve(null, object, expiresAt);
    }

    /**
     * Saves an object asynchronously with a TTL.
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<String> saveAsync(T object, long ttlMillis) {
        return core((Class<T>) object.getClass()).preserveAsync(null, object, ttlMillis);
    }

    /**
     * Marks an object as soft-deleted.
     */
    public static <T> void softDelete(String id, Class<T> clazz) {
        core(clazz).softDelete(id);
    }

    /**
     * Restores a soft-deleted object.
     */
    public static <T> void restore(String id, Class<T> clazz) {
        core(clazz).restore(id);
    }

    /**
     * Registers a schema migration for a specific class and version.
     */
    public static <T> void migration(Class<T> clazz, int version, Function<JsonObject, JsonObject> migrator) {
        core(clazz).registerMigration(version, migrator);
    }

    /**
     * Loads an object by ID.
     */
    public static <T> T load(String id, Class<T> clazz) {
        return core(clazz).extract(id).sync().orElse(null);
    }

    /**
     * Loads an object by ID asynchronously.
     */
    public static <T> CompletableFuture<T> loadAsync(String id, Class<T> clazz) {
        return core(clazz).extract(id).async().thenApply(opt -> opt.orElse(null));
    }

    /**
     * Lists all objects of a certain type.
     */
    public static <T> List<T> list(Class<T> clazz) {
        return core(clazz).extractAll();
    }

    /**
     * Deletes an object.
     */
    public static <T> void delete(String id, Class<T> clazz) {
        core(clazz).discard(id);
    }

    /**
     * Saves a batch of objects efficiently using a single SQL transaction.
     */
    @SuppressWarnings("unchecked")
    public static <T> void saveAll(Iterable<T> objects) {
        if (objects == null || !objects.iterator().hasNext()) return;
        T first = objects.iterator().next();
        core((Class<T>) first.getClass()).getShell().transaction(tx -> {
            for (T obj : objects) {
                tx.save(obj);
            }
        });
    }

    /**
     * Saves a batch of objects efficiently using a single SQL transaction with a TTL.
     */
    @SuppressWarnings("unchecked")
    public static <T> void saveAll(Iterable<T> objects, Duration ttl) {
        if (objects == null || !objects.iterator().hasNext()) return;
        T first = objects.iterator().next();
        core((Class<T>) first.getClass()).getShell().transaction(tx -> {
            for (T obj : objects) {
                tx.save(obj, ttl);
            }
        });
    }

    /**
     * Deletes a batch of objects efficiently using a single SQL transaction.
     */
    public static <T> void deleteAll(Class<T> clazz, Iterable<String> ids) {
        if (ids == null || !ids.iterator().hasNext()) return;
        core(clazz).getShell().transaction(tx -> {
            for (String id : ids) {
                tx.delete(id, clazz);
            }
        });
    }

    /**
     * Enables AES-256 encryption for a specific entity type.
     */
    public static <T> void encrypt(Class<T> clazz, String securityKey) {
        core(clazz).setSecurityKey(securityKey);
    }

    /**
     * Rotates the encryption key for a specific entity type.
     * It reads all data with the old key, updates the key, and saves them encrypted with the new key.
     */
    public static <T> void rotateKey(Class<T> clazz, String oldKey, String newKey) {
        encrypt(clazz, oldKey);
        long expected = core(clazz).count();
        List<T> allData = list(clazz);
        if (allData.size() != expected) {
            // Records that fail to decrypt are dropped by extractAll(). Re-keying now
            // would leave them permanently unreadable, so abort while oldKey still works.
            encrypt(clazz, oldKey);
            throw new com.cookie.caskara.exceptions.DatabaseException(
                "Aborting key rotation for " + clazz.getSimpleName() + ": only " + allData.size()
                + " of " + expected + " records could be decrypted with the old key. No data was changed.");
        }
        encrypt(clazz, newKey);
        for (T data : allData) {
            String id = getId(data);
            if (id != null) {
                save(id, data);
            } else {
                save(data);
            }
        }
    }

    /**
     * Executes a series of operations within a single SQL transaction on the default shell.
     */
    public static void transaction(Consumer<Transaction> action) {
        shell().transaction(action);
    }

    /**
     * Gets performance metrics for the default shell only.
     * For a server-wide view across every open shell, use {@link #globalStats()}.
     */
    public static Stats stats() {
        return shell().getStats();
    }

    /**
     * Aggregates metrics across every open shell into a detached snapshot.
     * <p>
     * The returned Stats is a copy — it does not keep updating and writing to it has no
     * effect on the live counters.
     */
    public static Stats globalStats() {
        Stats snapshot = new Stats();
        for (Shell shell : shells.values()) {
            snapshot.merge(shell.getStats());
        }
        return snapshot;
    }

    /**
     * Rebuilds an index on a specific JSON field for faster queries.
     */
    public static <T> void createIndex(Class<T> clazz, String jsonField) {
        core(clazz).createIndex(jsonField);
    }

    /**
     * Scans a package recursively to find and register all classes annotated with @CaskaraEntity.
     * This avoids having to call Caskara.register() manually for every entity.
     *
     * @param packageName The base package to scan, e.g., "com.mymod.data"
     */
    public static void scanPackage(String packageName) {
        PackageScanner.scanAndRegister(packageName);
    }



    /**
     * Exports the default shell data to a JSON file.
     */
    public static void exportShell(File file) {
        shell().exportToJson(file);
    }

    /**
     * Imports data from a JSON file into the default shell.
     */
    public static void importShell(File file) {
        shell().importFromJson(file);
    }

    /**
     * Utility: Gets the ID from an object (checking @Id, then id, uuid, uid fields).
     */
    public static String getId(Object object) {
        if (object == null) return null;

        // Shares Core's resolver so both sides agree on which field is the id,
        // including fields inherited from a base entity class.
        Field field = Core.findIdField(object.getClass());
        if (field == null) return null;
        try {
            field.setAccessible(true);
            Object val = field.get(object);
            return val != null ? val.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Registers the built-in Caskara management commands (/caskara) with the server.
     * Should be called during the plugin's setup() phase.
     */
    public static void registerCommands(CommandRegistry registry) {
        registry.registerCommand(new CaskaraCommand());
    }
}
