package com.cookie.caskara;

import com.cookie.caskara.db.BackupManager;
import com.cookie.caskara.db.Core;
import com.cookie.caskara.db.Query;
import com.cookie.caskara.db.Shell;
import com.cookie.caskara.db.Stats;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Caskara API - Shell & Core Paradigm.
 * Entry point for managing data shells and cores.
 */
public class Caskara {
    private static File dataFolder;
    private static final Map<String, Shell> shells = new HashMap<>();

    /**
     * Initializes the Caskara API.
     * @param folder The root folder for all shells.
     */
    public static void init(File folder) {
        dataFolder = folder;
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    /**
     * Opens a global Shell by name.
     */
    public static Shell shell(String name) {
        return shells.computeIfAbsent("global:" + name, 
            n -> new Shell(new File(dataFolder, "global/" + name + ".db")));
    }

    /**
     * Opens the default global Shell.
     */
    public static Shell shell() {
        return shell("default");
    }

    /**
     * Opens a Shell dedicated to a specific world.
     */
    public static Shell shell(World world, String name) {
        return shells.computeIfAbsent("world:" + world.getName() + ":" + name, 
            n -> new Shell(new File(dataFolder, "worlds/" + world.getName() + "/" + name + ".db")));
    }

    /**
     * Quickly gets a Core from the default Shell.
     */
    public static <T> Core<T> core(Class<T> clazz) {
        return shell().core(clazz);
    }

    /**
     * Returns a fluent Query builder for the given class.
     */
    public static <T> com.cookie.caskara.db.Query<T> query(Class<T> clazz) {
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
     * Saves an object with an automatic UUID.
     */
    @SuppressWarnings("unchecked")
    public static <T> String save(T object) {
        return core((Class<T>) object.getClass()).preserve(object);
    }

    /**
     * Saves an object with a TTL (Time To Live).
     */
    @SuppressWarnings("unchecked")
    public static <T> String save(T object, java.time.Duration ttl) {
        return core((Class<T>) object.getClass()).preserve(null, object, System.currentTimeMillis() + ttl.toMillis());
    }

    /**
     * Saves an object with an automatic UUID and specific TTL.
     */
    @SuppressWarnings("unchecked")
    public static <T> String save(T object, long ttlMillis) {
        return core((Class<T>) object.getClass()).preserve(null, object, ttlMillis);
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
    public static <T> void migration(Class<T> clazz, int version, java.util.function.Function<com.google.gson.JsonObject, com.google.gson.JsonObject> migrator) {
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
    public static <T> java.util.List<T> list(Class<T> clazz) {
        return core(clazz).extractAll();
    }

    /**
     * Deletes an object.
     */
    public static <T> void delete(String id, Class<T> clazz) {
        core(clazz).discard(id);
    }

    /**
     * Enables AES-256 encryption for a specific entity type.
     */
    public static <T> void encrypt(Class<T> clazz, String securityKey) {
        core(clazz).setSecurityKey(securityKey);
    }

    /**
     * Executes a series of operations within a single SQL transaction on the default shell.
     */
    public static void transaction(java.util.function.Consumer<com.cookie.caskara.db.Transaction> action) {
        shell().transaction(action);
    }

    /**
     * Gets performance metrics for the default shell.
     */
    public static com.cookie.caskara.db.Stats stats() {
        return shell().getStats();
    }

    /**
     * Creates a high-performance index on a JSON field.
     */
    public static <T> void createIndex(Class<T> clazz, String jsonField) {
        core(clazz).createIndex(jsonField);
    }

    /**
     * Enables automatic backups for the default shell.
     */
    public static void enableAutoBackup(int intervalMinutes) {
        new BackupManager(shell(), new File(dataFolder, "backups")).startAutoBackup(intervalMinutes);
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
     * Utility: Gets the ID from an object (checking id, uuid, uid fields).
     */
    public static String getId(Object object) {
        if (object == null) return null;
        for (String fName : new String[]{"id", "uuid", "uid"}) {
            try {
                java.lang.reflect.Field field = object.getClass().getDeclaredField(fName);
                field.setAccessible(true);
                Object val = field.get(object);
                if (val != null) return val.toString();
            } catch (Exception ignored) {}
        }
        return null;
    }
}
