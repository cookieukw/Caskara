# Caskara Documentation

Caskara is a **data engine library for Hytale mods**. It wraps SQLite with a JSON-NoSQL-style API, giving you schema-free persistence, ACID transactions, in-memory caching, AES encryption, schema migrations, real-time reactive observers, and performance metrics — all without writing SQL.

---

## Table of Contents

1. [Project Structure](#project-structure)
2. [Architecture Overview](#architecture-overview)
3. [Getting Started](#getting-started)
4. [Core Concepts: Shell & Core](#core-concepts-shell--core)
5. [API Reference](#api-reference)
   - [Caskara (Entry Point)](#1-caskara-entry-point)
   - [Shell](#2-shell)
   - [Core\<T\>](#3-coret)
   - [Query\<T\>](#4-queryt)
   - [Transaction](#5-transaction)
   - [Pearl\<T\>](#6-pearlt)
   - [Stats](#7-stats)
6. [Advanced Features](#advanced-features)
   - [AES-256 Encryption](#aes-256-encryption)
   - [Schema Migrations](#schema-migrations)
   - [TTL (Time To Live)](#ttl-time-to-live)
   - [Soft Delete & Restore](#soft-delete--restore)
   - [Hooks & Validators](#hooks--validators)
   - [Reactive Observers](#reactive-observers)
   - [Automated Backups](#automated-backups)
   - [Export & Import](#export--import)
7. [Exception Hierarchy](#exception-hierarchy)
8. [Data Types & Best Practices](#data-types--best-practices)

---

## Project Structure

```
Caskara/
├── build.gradle                  # Gradle build file; dependencies, deploy task, test config
├── gradle.properties             # Java version property
├── local.properties              # Local overrides (hytale.dir); not committed
├── libs/                         # Hytale server JAR (compile-only)
├── src/
│   ├── main/
│   │   ├── java/com/cookie/caskara/
│   │   │   ├── Caskara.java               # Main static API entry point
│   │   │   ├── MainPlugin.java            # Hytale plugin bootstrap
│   │   │   ├── db/
│   │   │   │   ├── Shell.java             # Database file/connection manager
│   │   │   │   ├── Core.java              # Type-scoped collection (table equivalent)
│   │   │   │   ├── Query.java             # Fluent query builder
│   │   │   │   ├── Transaction.java       # Atomic operation context
│   │   │   │   ├── Pearl.java             # Async result wrapper
│   │   │   │   ├── Stats.java             # Performance metrics
│   │   │   │   └── BackupManager.java     # Scheduled backup utility
│   │   │   ├── entities/
│   │   │   │   ├── User.java              # Example entity: simple user
│   │   │   │   ├── PlayerStats.java       # Example entity: nested stats object
│   │   │   │   └── FruitBasket.java       # Example entity: collection field
│   │   │   └── exceptions/
│   │   │       ├── CaskaraException.java  # Base unchecked exception
│   │   │       ├── DatabaseException.java # SQL/IO errors
│   │   │       └── ValidationException.java # Validator rejection
│   │   └── resources/                     # (placeholder for mod resources)
│   └── test/
│       └── java/com/cookie/caskara/
│           ├── ShellTest.java             # Shell connection & schema tests
│           ├── CoreCrudTest.java          # CRUD, TTL, soft-delete, validators
│           ├── CoreEncryptionTest.java    # AES encrypt/decrypt roundtrip
│           ├── CoreMigrationTest.java     # Schema migration pipeline
│           ├── QueryTest.java             # Query builder filter/sort/page
│           ├── TransactionTest.java       # ACID commit & rollback
│           ├── PearlTest.java             # Async result wrapper behavior
│           └── StatsTest.java             # Cache and query metrics
├── DOCS.md                       # This file
├── README.md                     # Quick-start overview
└── API.md                        # Concise API table reference
```

---

## Architecture Overview

Caskara uses a **Shell & Core** paradigm:

- **Shell** = one SQLite `.db` file. Manages the JDBC connection, locking, transactions, and exports.
- **Core\<T\>** = a typed "collection" inside a Shell. Handles serialization, caching, encryption, migrations, hooks, and observers for one specific Java class.

```
Plugin Code
    │
    ▼
Caskara (static API)
    │
    ├─► Shell ("global/default.db")
    │       └─► Core<PlayerData>   ─► LRU Cache ─► Gson (JSON) ─► AES-256 ─► SQLite
    │       └─► Core<QuestData>    ─► LRU Cache ─► Gson (JSON) ──────────►  SQLite
    │
    └─► Shell ("worlds/Orbis/spawn.db")
            └─► Core<ChunkData>   ─► LRU Cache ─► Gson (JSON) ──────────►  SQLite
```

All `Shell` operations are protected by a `ReentrantLock`. Async operations use **Virtual Threads** (`Executors.newVirtualThreadPerTaskExecutor()`).

---

## Getting Started

### 1. Add Caskara to your mod

Place `caskara-x.x.x.jar` in your mod's classpath or the Hytale `UserData/Mods` folder (the `deploy` Gradle task does this automatically).

### 2. Initialize once on plugin startup

```java
public class MyPlugin extends JavaPlugin {
    @Override
    protected void setup() {
        // All databases will be stored under this folder
        Caskara.init(new File("mods/MyMod/data"));
    }
}
```

> **Important**: `Caskara.init()` must be called **before** any other Caskara method.

### 3. Define your entity

```java
public class PlayerProfile {
    public String id;   // Caskara uses this as the primary key
    public String name;
    public int level;
    public boolean vip;

    public PlayerProfile() {}  // Required by Gson for deserialization
    public PlayerProfile(String name, int level) {
        this.name = name;
        this.level = level;
    }
}
```

### 4. Save, load, query, delete

```java
// Save (auto-generates UUID as id)
PlayerProfile p = new PlayerProfile("Cookie", 10);
String id = Caskara.save(p);

// Load
PlayerProfile loaded = Caskara.load(id, PlayerProfile.class);

// List all
List<PlayerProfile> all = Caskara.list(PlayerProfile.class);

// Update (same id -> overwrites)
loaded.level = 11;
Caskara.save(id, loaded);

// Delete
Caskara.delete(id, PlayerProfile.class);
```

### 5. Build and deploy

```bash
# Compile, package, and deploy to Hytale mods folder
./gradlew jar

# Run tests
./gradlew test
```

---

## Core Concepts: Shell & Core

### Shell

A `Shell` maps to one SQLite database file. You can have multiple Shells:

```java
Shell global   = Caskara.shell("global");     // → global/global.db
Shell players  = Caskara.shell("players");    // → global/players.db
Shell world    = Caskara.shell(world, "data");// → worlds/<name>/data.db
```

A Shell holds:

- The JDBC SQLite connection (thread-safe via `ReentrantLock`)
- A map of `Core<?>` instances (one per Java class)
- A `Stats` object tracking performance
- A scheduled cleanup task that purges expired (TTL) records every minute

### Core\<T\>

A `Core<T>` is scoped to a single entity type. Its `typeName` is derived from the simple class name (lowercased). Internally it manages:

- LRU in-memory cache (max 500 entries)
- Before/after save hooks
- Validators
- Encryption/decryption
- Schema migration pipeline
- Reactive observers

Access a Core via:

```java
Core<PlayerProfile> core = Caskara.core(PlayerProfile.class);
// Or from a specific shell:
Core<PlayerProfile> core = Caskara.shell("players").core(PlayerProfile.class);
```

---

## API Reference

### 1. `Caskara` (Entry Point)

All methods in this class are **static**. They operate on the **default global shell** unless otherwise noted.

| Method                                                                        | Description                                                          |
| :---------------------------------------------------------------------------- | :------------------------------------------------------------------- |
| `init(File folder)`                                                           | **Required first call.** Initializes the root data folder.           |
| `shell(String name)`                                                          | Opens (or retrieves) a named global Shell.                           |
| `shell()`                                                                     | Opens the default global Shell (`global/default.db`).                |
| `shell(World world, String name)`                                             | Opens a world-scoped Shell.                                          |
| `core(Class<T> clazz)`                                                        | Returns the Core for `clazz` from the default Shell.                 |
| `query(Class<T> clazz)`                                                       | Returns a Query builder for `clazz`.                                 |
| `save(T object)`                                                              | Saves with auto-generated UUID. Returns the assigned ID.             |
| `save(String id, T object)`                                                   | Saves with a specific ID.                                            |
| `save(T object, Duration ttl)`                                                | Saves with a Time-To-Live. The record is auto-deleted after expiry.  |
| `save(T object, long ttlMillis)`                                              | Same as above using milliseconds.                                    |
| `saveAsync(T object, long ttlMillis)`                                         | Non-blocking save with TTL. Returns `CompletableFuture<String>`.     |
| `load(String id, Class<T> clazz)`                                             | Loads by ID. Returns `null` if not found or expired.                 |
| `loadAsync(String id, Class<T> clazz)`                                        | Non-blocking load. Returns `CompletableFuture<T>`.                   |
| `list(Class<T> clazz)`                                                        | Returns a `List<T>` of all active records of that type.              |
| `delete(String id, Class<T> clazz)`                                           | Physically deletes the record.                                       |
| `softDelete(String id, Class<T> clazz)`                                       | Hides the record without removing it from SQLite.                    |
| `restore(String id, Class<T> clazz)`                                          | Unhides a soft-deleted record.                                       |
| `transaction(Consumer<Transaction> action)`                                   | Executes operations atomically on the default Shell.                 |
| `stats()`                                                                     | Returns the `Stats` object for the default Shell.                    |
| `createIndex(Class<T> clazz, String jsonField)`                               | Creates a computed SQL index for faster JSON field queries.          |
| `enableAutoBackup(int intervalMinutes)`                                       | Schedules periodic `.bak` file copies of the default Shell.          |
| `exportShell(File file)`                                                      | Dumps all data from the default Shell to a JSON file.                |
| `importShell(File file)`                                                      | Bulk-imports records from a JSON file into the default Shell.        |
| `encrypt(Class<T> clazz, String key)`                                         | Enables AES-128 encryption for all records of `clazz`.               |
| `migration(Class<T> clazz, int version, Function<JsonObject, JsonObject> fn)` | Registers a schema migration function.                               |
| `getId(Object object)`                                                        | Reflectively reads the `id`, `uuid`, or `uid` field from any object. |

---

### 2. `Shell`

`Shell` is usually accessed indirectly via `Caskara.shell(...)`. Direct access is useful for multi-shell setups.

```java
Shell playersShell = Caskara.shell("players");
Core<PlayerProfile> core = playersShell.core(PlayerProfile.class);
playersShell.transaction(tx -> { ... });
Stats stats = playersShell.getStats();
playersShell.exportToJson(new File("backup.json"));
playersShell.close(); // important! shuts down connection and scheduler
```

| Method                                      | Description                                                      |
| :------------------------------------------ | :--------------------------------------------------------------- |
| `core(Class<T> clazz)`                      | Returns the Core for the specified class.                        |
| `transaction(Consumer<Transaction> action)` | ACID transaction. See [Transaction](#5-transaction).             |
| `getStats()`                                | Returns the Shell's `Stats` tracker.                             |
| `exportToJson(File file)`                   | Exports all records to JSON.                                     |
| `importFromJson(File file)`                 | Imports records from JSON.                                       |
| `getConnection()`                           | Returns the raw JDBC `Connection` (advanced use only).           |
| `runInLock(Supplier<R> action)`             | Executes an action under the Shell's `ReentrantLock`.            |
| `close()`                                   | Closes the JDBC connection. Always call this on plugin shutdown. |

---

### 3. `Core<T>`

```java
Core<PlayerProfile> core = Caskara.core(PlayerProfile.class);
```

| Method                                                                | Description                                                            |
| :-------------------------------------------------------------------- | :--------------------------------------------------------------------- |
| `preserve(T element)`                                                 | Saves with auto-UUID. Returns the ID.                                  |
| `preserve(String id, T element)`                                      | Saves with a specific ID.                                              |
| `preserve(String id, T element, Long expiresAtMs)`                    | Saves with an absolute expiry timestamp.                               |
| `preserveAsync(String id, T element)`                                 | Non-blocking save. Returns `CompletableFuture<String>`.                |
| `extract(String id)`                                                  | Loads by ID, returns `Pearl<T>`.                                       |
| `extractAll()`                                                        | Returns `List<T>` of all active, non-expired, non-deleted records.     |
| `discard(String id)`                                                  | Physically deletes the record.                                         |
| `softDelete(String id)`                                               | Sets `deleted_at` timestamp; record is hidden from queries.            |
| `restore(String id)`                                                  | Clears `deleted_at`; record becomes visible again.                     |
| `query()`                                                             | Returns a `Query<T>` builder for this Core.                            |
| `createIndex(String jsonField)`                                       | Creates a computed SQL index: `idx_<type>_<field>`.                    |
| `setSecurityKey(String key)`                                          | Enables AES-128 encryption on this Core.                               |
| `addValidator(Predicate<T> validator)`                                | Throws `ValidationException` if the predicate returns `false` on save. |
| `onBeforeSave(BiConsumer<String, T> hook)`                            | Fires before every `preserve()` call.                                  |
| `onAfterSave(BiConsumer<String, T> hook)`                             | Fires after every successful `preserve()` call.                        |
| `onBeforeDelete(Consumer<String> hook)`                               | Fires before every `discard()` call.                                   |
| `observe(String id, BiConsumer<String, T> observer)`                  | Fires when a specific record is saved or updated.                      |
| `observeAll(BiConsumer<String, T> observer)`                          | Fires when ANY record of this type is saved or updated.                |
| `registerMigration(int version, Function<JsonObject, JsonObject> fn)` | Registers a migration applied lazily on next read.                     |

---

### 4. `Query<T>`

Fluent builder. Obtained via `Caskara.query(Class)` or `core.query()`.

```java
List<PlayerProfile> results = Caskara.query(PlayerProfile.class)
    .field("vip", true)
    .fieldGreaterThan("level", 5)
    .orderBy("level", Query.Order.DESC)
    .page(1, 20)
    .fetch();
```

| Method                                        | Description                                               |
| :-------------------------------------------- | :-------------------------------------------------------- |
| `field(String name, Object value)`            | Exact match: `json_extract(json, '$.name') = value`.      |
| `fieldGreaterThan(String name, Object value)` | Greater-than comparison on a JSON field.                  |
| `fieldLessThan(String name, Object value)`    | Less-than comparison on a JSON field.                     |
| `fieldIn(String name, List<Object> values)`   | Matches any value in the list (SQL `IN`).                 |
| `fieldContains(String name, String text)`     | SQL `LIKE '%text%'` on a JSON string field.               |
| `orderBy(String field, Order direction)`      | Sort by a JSON field. Use `Query.Order.ASC` or `DESC`.    |
| `limit(int n)`                                | Restrict result count.                                    |
| `offset(int n)`                               | Skip the first `n` results.                               |
| `page(int page, int size)`                    | Convenience pagination. `page(1, 20)` → first 20 results. |
| `fetch()`                                     | Executes and returns `List<T>` (blocking).                |
| `fetchAsync()`                                | Non-blocking; returns `CompletableFuture<List<T>>`.       |
| `fetchFirst()`                                | Returns `Pearl<T>` with the first result.                 |

**Performance tip**: Call `Caskara.createIndex(MyClass.class, "fieldName")` before querying a field repeatedly to get O(1) lookups via a computed SQL index.

---

### 5. `Transaction`

Passed to the lambda in `Caskara.transaction()`. All operations are batched into a single SQL transaction — either all succeed or all are rolled back.

```java
Caskara.transaction(tx -> {
    Wallet sender   = tx.load("player_1", Wallet.class);
    Wallet receiver = tx.load("player_2", Wallet.class);
    sender.balance   -= 500;
    receiver.balance += 500;
    tx.save("player_1", sender);
    tx.save("player_2", receiver);
});
```

| Method                              | Description                          |
| :---------------------------------- | :----------------------------------- |
| `save(T object)`                    | Atomically preserves with auto-UUID. |
| `save(String id, T object)`         | Atomically preserves with given ID.  |
| `delete(String id, Class<T> clazz)` | Atomically deletes a record.         |
| `load(String id, Class<T> clazz)`   | Loads within the transaction lock.   |

If any exception is thrown inside the lambda, **all changes are rolled back** and the in-memory cache is cleared.

---

### 6. `Pearl<T>`

Wraps the result of an async or sync data operation. Think of it as a more ergonomic `Optional<CompletableFuture<T>>`.

```java
Pearl<PlayerProfile> pearl = Caskara.core(PlayerProfile.class).extract("player-123");

// Blocking retrieval (5s timeout)
Optional<PlayerProfile> opt = pearl.sync();

// Non-blocking
pearl.async().thenAccept(optProfile -> { ... });

// Inline action
pearl.ifFound(profile -> System.out.println("Found: " + profile.name));

// Transform result type
Pearl<String> namePearl = pearl.map(p -> p.name);
```

| Method                        | Description                                                                                          |
| :---------------------------- | :--------------------------------------------------------------------------------------------------- |
| `sync()`                      | Blocks until result is ready (max 5s). Returns `Optional<T>`. Throws `DatabaseException` on failure. |
| `async()`                     | Non-blocking. Returns `CompletableFuture<Optional<T>>`.                                              |
| `ifFound(Consumer<T> action)` | Executes action synchronously if value is present.                                                   |
| `map(Function<T, R> mapper)`  | Transforms the wrapped type. Returns `Pearl<R>`.                                                     |

---

### 7. `Stats`

Tracks internal performance metrics for a Shell.

```java
Stats stats = Caskara.stats();
System.out.println("Cache hit rate: " + stats.getCacheHitRate() * 100 + "%");
System.out.println("Avg query time: " + stats.getAverageQueryTimeMs() + "ms");
System.out.println("Total queries:  " + stats.getTotalQueries());
```

| Method                    | Return Type | Description                                                    |
| :------------------------ | :---------- | :------------------------------------------------------------- |
| `getCacheHitRate()`       | `double`    | Fraction of `extract()` calls served from LRU cache (0.0–1.0). |
| `getAverageQueryTimeMs()` | `double`    | Mean SQL query execution time in milliseconds.                 |
| `getTotalQueries()`       | `long`      | Total number of `fetch()` calls executed since shell init.     |
| `getCacheHits()`          | `long`      | Raw count of cache hits.                                       |
| `getCacheMisses()`        | `long`      | Raw count of cache misses.                                     |

---

## Advanced Features

### AES-256 Encryption

Caskara can store data as AES-encrypted Base64 blobs. The key is derived using SHA-256.

```java
// Call before any save operations for that class
Caskara.encrypt(SecretToken.class, "my-super-secret-password");

// From this point, all saves are encrypted, all loads are decrypted automatically
Caskara.save(new SecretToken("discord-bot-token", "xyzABC123"));
SecretToken loaded = Caskara.load("my-token", SecretToken.class); // decrypted
```

> **Warning**: Changing the encryption key after data is already stored will make existing records unreadable. Always use the same key across restarts.

---

### Schema Migrations

Register migration functions that transform JSON from old schema versions on the fly, the first time an old record is read.

```java
// PlayerProfile v1 had no "rank" field. Add it as default "BRONZE" for v1 records.
Caskara.migration(PlayerProfile.class, 2, json -> {
    if (!json.has("rank")) {
        json.addProperty("rank", "BRONZE");
    }
    return json;
});

// Chain multiple migrations:
Caskara.migration(PlayerProfile.class, 3, json -> {
    // rename field "vip" → "premium"
    if (json.has("vip")) {
        json.addProperty("premium", json.get("vip").getAsBoolean());
        json.remove("vip");
    }
    return json;
});
```

Migrations are applied **lazily** — only when a record with an older `version` is read. The record is then re-saved at the current schema version automatically.

---

### TTL (Time To Live)

Records can auto-expire after a set duration. A background worker cleans them up every minute.

```java
// Expire after 30 minutes
Caskara.save(new TempBuff("fire_resistance"), Duration.ofMinutes(30));

// Or in milliseconds
Caskara.save(new TempBuff("speed"), 60_000L);
```

A record past its expiry is **invisible** to all queries and `load()` calls, even if not yet physically deleted. The cleanup task physically removes them within ~1 minute.

---

### Soft Delete & Restore

Non-destructive deletion. The data stays in the DB but is filtered out of all results.

```java
Caskara.softDelete("player-123", PlayerProfile.class);
// Now: load() returns null, list() excludes it, queries ignore it

Caskara.restore("player-123", PlayerProfile.class);
// Now: record is fully visible again
```

---

### Hooks & Validators

```java
Core<PlayerProfile> core = Caskara.core(PlayerProfile.class);

// Reject invalid data before it reaches the DB
core.addValidator(p -> p.level > 0 && p.name != null && !p.name.isBlank());

// Fire custom logic before every save
core.onBeforeSave((id, p) -> p.name = p.name.trim());

// Fire custom logic after every successful save
core.onAfterSave((id, p) -> AuditLog.record("Saved player: " + id));

// Fire custom logic before a physical delete
core.onBeforeDelete(id -> AuditLog.record("Deleted player: " + id));
```

`ValidationException` is thrown if any validator returns `false`, preventing the save.

---

### Reactive Observers

Subscribe to changes on specific records or all records of a type.

```java
Core<PlayerProfile> core = Caskara.core(PlayerProfile.class);

// Watch a specific player
core.observe("player-123", (id, p) -> {
    System.out.println("Player " + id + " was updated! New level: " + p.level);
});

// Watch all players
core.observeAll((id, p) -> {
    Websocket.broadcast("player_updated", id);
});
```

Observers fire synchronously after every successful `preserve()`.

---

### Automated Backups

```java
// Back up every 15 minutes
Caskara.enableAutoBackup(15);
```

Backups are stored as `<shellname>.db.<timestamp>.bak` in `<dataFolder>/backups/`. The Shell is locked during the copy to ensure consistency.

---

### Export & Import

```java
// Export the entire default shell to JSON
Caskara.exportShell(new File("export.json"));

// Import records from a JSON file (uses INSERT OR REPLACE)
Caskara.importShell(new File("export.json"));

// Per-shell:
Caskara.shell("players").exportToJson(new File("players_backup.json"));
```

The JSON format is an array of `{id, type, json}` objects.

---

## Exception Hierarchy

All exceptions are **unchecked** (extend `RuntimeException`) so they don't pollute your method signatures. Catch them in critical areas.

```
RuntimeException
└── CaskaraException          ← Base for all Caskara errors
    ├── DatabaseException     ← SQL errors, I/O failures, transaction rollbacks
    └── ValidationException   ← Thrown when an addValidator() predicate returns false
```

```java
try {
    Caskara.transaction(tx -> { ... });
} catch (DatabaseException e) {
    logger.error("Transaction failed: " + e.getMessage(), e);
} catch (ValidationException e) {
    player.sendMessage("Invalid data: " + e.getMessage());
}
```

---

## Data Types & Best Practices

### Supported Types (via Gson)

- **Primitives**: `int`, `long`, `double`, `boolean`, `float`, etc.
- **Strings**: `String`
- **Collections**: `List<T>`, `Set<T>`, `Map<K, V>` — serialized automatically
- **Nested objects**: Any Java class. Serialized as JSON sub-trees.
- **Transient fields**: Fields marked `transient` are excluded from JSON.

### Primary Keys

Caskara auto-discovers the ID by looking for fields named `id`, `uuid`, or `uid` in order. The ID is synced back to the field after `preserve()`.

```java
// Best practice: always include this field
public String id;
```

### No-arg Constructor Required

Gson needs a default constructor to deserialize JSON back to your class:

```java
public PlayerProfile() {}   // ← mandatory!
public PlayerProfile(String name) { this.name = name; }
```

### Threading

- **Shells** are thread-safe via `ReentrantLock`. Safe to call from multiple threads.
- **Async paths** use Java 21 Virtual Threads (lightweight; no manual thread pool tuning needed).
- **Transactions** hold the lock for their full duration — keep them fast and focused.

### Performance Tips

1. Use `createIndex()` before querying JSON fields repeatedly.
2. Prefer `extractAll()` + in-memory filtering for small datasets rather than many individual `load()` calls.
3. Use `loadAsync()` / `fetchAsync()` in event handlers to avoid blocking the server thread.
4. Monitor `stats().getCacheHitRate()` — below 50% may indicate the cache is too small for your dataset.

### When NOT to Use Caskara

- **Large BLOBs** (images, audio): use Hytale's asset pipeline.
- **Relational data** with many joins: use raw SQL or a dedicated ORM.
- **Multi-server shared state**: use Redis or a dedicated external DB; SQLite is single-writer.
