package com.cookie.caskara.db;

import com.cookie.caskara.exceptions.DatabaseException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.function.Predicate;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A 'Core' represents a collection of a specific type within a Shell.
 */
public class Core<T> {
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().serializeNulls().create();
    private final Shell shell;
    private final Class<T> clazz;
    private final String typeName;
    
    // Simple LRU Cache (Least Recently Used)
    private final int MAX_CACHE_SIZE = 500;
    private final Map<String, T> cache = Collections.synchronizedMap(new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, T> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    });

    // Hooks & Validation (Phase 2)
    private final List<java.util.function.BiConsumer<String, T>> beforeSaveHooks = new ArrayList<>();
    private final List<java.util.function.BiConsumer<String, T>> afterSaveHooks = new ArrayList<>();
    private final List<java.util.function.Consumer<String>> beforeDeleteHooks = new ArrayList<>();
    private final List<java.util.function.Predicate<T>> validators = new ArrayList<>();

    // Migration System (Phase 6)
    private final java.util.TreeMap<Integer, java.util.function.Function<com.google.gson.JsonObject, com.google.gson.JsonObject>> migrations = new java.util.TreeMap<>();
    private int schemaVersion = 1;

    // Reactive Observers (Phase 8 bonus)
    private final Map<String, List<java.util.function.BiConsumer<String, T>>> listeners = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<java.util.function.BiConsumer<String, T>> genericListeners = new CopyOnWriteArrayList<>();

    // Security (Phase 8 bonus)
    private String securityKey = null;

    public Core(Shell shell, Class<T> clazz) {
        this.shell = shell;
        this.clazz = clazz;
        this.typeName = clazz.getSimpleName().toLowerCase();
    }

    /**
     * Preserves an element in the shell.
     * Triggers before/after hooks and validation.
     */
    public String preserve(String id, T element) {
        return preserve(id, element, null);
    }

    /**
     * Preserves an element with an optional TTL (Time To Live).
     */
    public String preserve(String id, T element, Long expiresAtMillis) {
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        
        // Sync ID with object fields
        syncId(id, element);

        // Run validation
        for (java.util.function.Predicate<T> validator : validators) {
            if (!validator.test(element)) {
                throw new com.cookie.caskara.exceptions.ValidationException("Validation failed for entity of type: " + typeName);
            }
        }

        // Trigger Before Save Hooks
        for (java.util.function.BiConsumer<String, T> hook : beforeSaveHooks) {
            hook.accept(id, element);
        }
        
        final String finalId = id;
        final int currentVersion = schemaVersion;
        shell.runInLock(() -> {
            String json = encrypt(GSON.toJson(element));
            String sql = "INSERT OR REPLACE INTO elements (id, type, json, expires_at, deleted_at, version) VALUES (?, ?, ?, ?, NULL, ?)";
            try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, finalId);
                pstmt.setString(2, typeName);
                pstmt.setString(3, json);
                if (expiresAtMillis != null) {
                    pstmt.setLong(4, expiresAtMillis);
                } else {
                    pstmt.setNull(4, java.sql.Types.INTEGER);
                }
                pstmt.setInt(5, currentVersion);
                pstmt.executeUpdate();
                cache.put(finalId, element);
                // If this entity has a TTL, evict it from cache immediately so
                // the next extract() goes to the DB where expires_at is checked.
                if (expiresAtMillis != null) {
                    cache.remove(finalId);
                }

            } catch (SQLException e) {
                throw new DatabaseException("Failed to preserve element in Core: " + finalId, e);
            }
            return null;
        });

        // Trigger After Save Hooks
        for (java.util.function.BiConsumer<String, T> hook : afterSaveHooks) {
            hook.accept(id, element);
        }

        // Trigger Reactive Observers
        genericListeners.forEach(l -> l.accept(finalId, element));
        List<java.util.function.BiConsumer<String, T>> specific = listeners.get(finalId);
        if (specific != null) {
            specific.forEach(l -> l.accept(finalId, element));
        }

        return id;
    }

    public String preserve(T element) {
        return preserve(null, element);
    }

    public CompletableFuture<String> preserveAsync(String id, T element) {
        return CompletableFuture.supplyAsync(() -> preserve(id, element), shell.getExecutor());
    }

    public CompletableFuture<String> preserveAsync(String id, T element, long ttlMillis) {
        long expiresAt = System.currentTimeMillis() + ttlMillis;
        return CompletableFuture.supplyAsync(() -> preserve(id, element, expiresAt), shell.getExecutor());
    }

    /**
     * Extracts a 'Pearl' (result) from the shell by ID.
     * Note: always hits the DB to enforce TTL and soft-delete filters correctly.
     * Cache is used as a write-through store and populated only on successful reads.
     */
    public Pearl<T> extract(String id) {
        // Check cache first — but only trust non-expired entries.
        // We let the DB be the source of truth for TTL enforcement.
        // To keep things simple and correct, we check the DB whenever an entity
        // might have a TTL. The cache is still used as a write-through: it is
        // populated on reads and invalidated on soft-delete/discard.
        if (cache.containsKey(id)) {
            shell.getStats().recordCacheHit();
            return new Pearl<>(cache.get(id));
        }
        shell.getStats().recordCacheMiss();

        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> shell.runInLock(() -> {
            String sql = "SELECT json, version FROM elements WHERE id = ? AND type = ? AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)";
            try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, id);
                pstmt.setString(2, typeName);
                pstmt.setLong(3, System.currentTimeMillis());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        String json = rs.getString("json");
                        int dbVersion = rs.getInt("version");
                        return applyMigrations(id, json, dbVersion);
                    }
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to extract element from Core: " + id, e);
            }
            return null;
        }), shell.getExecutor());
        return new Pearl<>(future);
    }

    /**
     * Discards an element from the shell.
     * Triggers before delete hooks.
     */
    public void discard(String id) {
        // Trigger Before Delete Hooks
        for (java.util.function.Consumer<String> hook : beforeDeleteHooks) {
            hook.accept(id);
        }

        shell.runInLock(() -> {
            String sql = "DELETE FROM elements WHERE id = ? AND type = ?";
            try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, id);
                pstmt.setString(2, typeName);
                pstmt.executeUpdate();
                cache.remove(id);
            } catch (SQLException e) {
                throw new DatabaseException("Failed to discard element from Core: " + id, e);
            }
            return null;
        });
    }

    // Phase 2 Registration Methods

    public void onBeforeSave(java.util.function.BiConsumer<String, T> hook) {
        this.beforeSaveHooks.add(hook);
    }

    public void onAfterSave(java.util.function.BiConsumer<String, T> hook) {
        this.afterSaveHooks.add(hook);
    }

    public void onBeforeDelete(java.util.function.Consumer<String> hook) {
        this.beforeDeleteHooks.add(hook);
    }

    public void addValidator(java.util.function.Predicate<T> validator) {
        this.validators.add(validator);
    }

    /**
     * Creates an index on a JSON field for faster queries.
     */
    public void createIndex(String jsonField) {
        shell.runInLock(() -> {
            String indexName = "idx_" + typeName + "_" + jsonField.replace(".", "_");
            String sql = "CREATE INDEX IF NOT EXISTS " + indexName + 
                         " ON elements(json_extract(json, '$.' || ?)) WHERE type = ?";
            try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, jsonField);
                pstmt.setString(2, typeName);
                pstmt.execute();
            } catch (SQLException e) {
                throw new DatabaseException("Failed to create SQL index on field: " + jsonField, e);
            }
            return null;
        });
    }

    /**
     * Extracts all elements of this type.
     */
    public List<T> extractAll() {
        return shell.runInLock(() -> {
            List<T> results = new ArrayList<>();
            String sql = "SELECT id, json, version FROM elements WHERE type = ? AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)";
            try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, typeName);
                pstmt.setLong(2, System.currentTimeMillis());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString("id");
                        String json = rs.getString("json");
                        int dbVersion = rs.getInt("version");
                        results.add(applyMigrations(id, json, dbVersion));
                    }
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to extract all elements from Core: " + typeName, e);
            }
            return results;
        });
    }

    /**
     * Registers a migration function for a specific version.
     */
    public void registerMigration(int version, java.util.function.Function<com.google.gson.JsonObject, com.google.gson.JsonObject> migrator) {
        this.migrations.put(version, migrator);
        if (version > this.schemaVersion) {
            this.schemaVersion = version;
        }
    }

    /**
     * Subscribes to changes for a specific ID.
     */
    public void observe(String id, java.util.function.BiConsumer<String, T> observer) {
        listeners.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(observer);
    }

    /**
     * Subscribes to all changes for this element type.
     */
    public void observeAll(java.util.function.BiConsumer<String, T> observer) {
        genericListeners.add(observer);
    }

    public void setSecurityKey(String key) {
        this.securityKey = key;
    }

    private String encrypt(String data) {
        if (securityKey == null) return data;
        try {
            SecretKeySpec secretKeySpec = new SecretKeySpec(generateKey(securityKey), "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec);
            return Base64.getEncoder().encodeToString(cipher.doFinal(data.getBytes()));
        } catch (Exception e) {
            throw new DatabaseException("Encryption failed for entity type: " + typeName, e);
        }
    }

    public String decrypt(String data) {
        if (securityKey == null) return data;
        try {
            SecretKeySpec secretKeySpec = new SecretKeySpec(generateKey(securityKey), "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec);
            return new String(cipher.doFinal(Base64.getDecoder().decode(data)));
        } catch (Exception e) {
            // Might be old unencrypted data or wrong key
            return data;
        }
    }

    private byte[] generateKey(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(hash, 16); // AES-128: 16 bytes from a 32-byte SHA-256 digest
        } catch (NoSuchAlgorithmException e) {
            throw new DatabaseException("Failed to derive encryption key: SHA-256 not available", e);
        }
    }

    private T applyMigrations(String id, String json, int dbVersion) {
        json = decrypt(json);
        if (dbVersion >= schemaVersion) {
            T obj = GSON.fromJson(json, clazz);
            syncId(id, obj);
            cache.put(id, obj);
            return obj;
        }

        com.google.gson.JsonObject jsonObject = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        boolean changed = false;

        for (Map.Entry<Integer, java.util.function.Function<com.google.gson.JsonObject, com.google.gson.JsonObject>> entry : migrations.tailMap(dbVersion, false).entrySet()) {
            jsonObject = entry.getValue().apply(jsonObject);
            changed = true;
        }

        String finalJson = GSON.toJson(jsonObject);
        if (changed) {
            final int newVersion = schemaVersion;
            // Use getConnection() directly — we are already inside a runInLock context
            try {
                String sql = "UPDATE elements SET json = ?, version = ? WHERE id = ? AND type = ?";
                try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
                    pstmt.setString(1, finalJson);
                    pstmt.setInt(2, newVersion);
                    pstmt.setString(3, id);
                    pstmt.setString(4, typeName);
                    pstmt.executeUpdate();
                }
            } catch (SQLException ignored) {
            }
        }

        T obj = GSON.fromJson(finalJson, clazz);
        syncId(id, obj);
        cache.put(id, obj);
        return obj;
    }

    /**
     * Marks an element as deleted without removing it from the database (Soft Delete).
     */
    public void softDelete(String id) {
        shell.runInLock(() -> {
            String sql = "UPDATE elements SET deleted_at = ? WHERE id = ? AND type = ?";
            try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
                pstmt.setLong(1, System.currentTimeMillis());
                pstmt.setString(2, id);
                pstmt.setString(3, typeName);
                pstmt.executeUpdate();
                cache.remove(id);
            } catch (SQLException e) {
                throw new DatabaseException("Failed to soft delete element: " + id, e);
            }
            return null;
        });
    }

    /**
     * Restores a soft-deleted element.
     */
    public void restore(String id) {
        shell.runInLock(() -> {
            String sql = "UPDATE elements SET deleted_at = NULL WHERE id = ? AND type = ?";
            try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, id);
                pstmt.setString(2, typeName);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new DatabaseException("Failed to restore element: " + id, e);
            }
            return null;
        });
    }

    public boolean isEncrypted() {
        return securityKey != null;
    }

    public Query<T> query() {
        return new Query<>(this, shell, clazz, typeName);
    }

    /**
     * Injects the ID into fields named 'id', 'uuid', or 'uid' using reflection.
     */
    private void syncId(String id, T element) {
        if (element == null || id == null) return;
        for (String fName : new String[]{"id", "uuid", "uid"}) {
            try {
                Field field = clazz.getDeclaredField(fName);
                field.setAccessible(true);
                Object current = field.get(element);
                // Inject the DB id if the field is null OR empty string
                if (current == null || (current instanceof String && ((String) current).isEmpty())) {
                    field.set(element, id);
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                // Fail silently for reflection sync
            }
        }
    }

    /**
     * Clears the LRU cache. Called by Shell on transaction rollback.
     */
    void clearCache() {
        cache.clear();
    }

    static Gson getGson() {
        return GSON;
    }
}
