# Caskara: A Data Engine for Hytale Mods

Caskara is a data engine built specifically for the modding ecosystem of Hytale. It combines the reliability of SQLite with the flexibility of JSON-style data structures, giving mod developers a fast and practical way to store and manage persistent data.

Instead of relying on external services or complex setup, Caskara runs locally alongside the mod. This makes it easy to integrate while keeping performance stable even when a server is handling large amounts of game events or player data.

> **BETA RELEASE:** Caskara is currently in Beta. While it has undergone extensive testing, it may still contain undiscovered bugs or inconsistencies. Please report any issues you find.

📖 **[Read the Full API Documentation](https://caskara.netlify.app/)**

***

## 📦 Architecture Overview

Caskara uses a unique **Shell & Core** architecture to manage data. Every database file is a "Shell", and every entity type within that shell is a "Core".

### Flow (simplified)

```
Plugin Code → Caskara API → Shell Controller
↓
┌───────────────┐
│   Shells      │
│ global.db     │
│ players.db    │
└──────┬────────┘
↓
┌───────────────┐
│    Cores      │
│ Quests        │
│ Factions      │
│ PlayerStats   │
└───────────────┘

Internal:
LRU Cache ↔ JSON Serializer ↔ AES-128 Encryption ↔ SQLite Storage
```

***

## 🏁 Beginner's Guide: Basic CRUD

Managing data with Caskara is intentionally simple. You don't need to write SQL or define schemas.

### 1\. Model your data

Any Java class with a default constructor can be a Caskara entity. You can optionally use annotations to configure it dynamically:

```
import com.cookie.caskara.annotations.*;

@CaskaraEntity(shell = "quests") // Store in quests.db instead of global.db
@Index("title")                  // Create an index for faster title lookups
@TTL(minutes = 60)               // Quests expire after 60 minutes by default
public class Quest {
    @Id
    public String questId;       // @Id marks the primary key

    public String title;
    public boolean completed;
}
```

### 2\. Basic Operations

```
// Save (Create or Update)
Quest q = new Quest("Fire Dragon", false);
Caskara.save(q);

// Load (Read)
Quest loaded = Caskara.load("Fire Dragon", Quest.class);

// List All
List<Quest> allQuests = Caskara.list(Quest.class);

// Delete (Discard)
Caskara.delete("Fire Dragon", Quest.class);
```

***

## 🛡️ Advanced Features

### ACID Transactions

Ensure multiple operations either all succeed or all fail. Perfect for economic transfers.

```
Caskara.transaction(tx -> {
    Wallet p1 = tx.load("uuid1", Wallet.class);
    Wallet p2 = tx.load("uuid2", Wallet.class);

    p1.balance -= 100;
    p2.balance += 100;

    tx.save(p1);
    tx.save(p2); // If this throws an exception, p1's balance is ROLLED BACK automatically!
});
```

### Hooks & Validation

Automate logic before or after data is touched.

```
var core = Caskara.core(Player.class);

// Stop bad data from being saved
core.addValidator(p -> p.level > 0);

// Log activity automatically
core.onAfterSave((id, p) -> Logger.info("Player " + p.name + " was saved."));
```

### Object Lifecycle: TTL & Soft Delete

```
// This record will be physically deleted after 30 minutes by a background worker
Caskara.save(tempBuff, Duration.ofMinutes(30));

// Non-destructive delete. The data stays in DB but is ignored by Queries/Loads.
Caskara.softDelete("mod-123", ModData.class);
Caskara.restore("mod-123", ModData.class); // Bring it back!
```

***

## 🔐 Security: Transparent Encryption

Secure sensitive data (like Discord tokens or private keys) with AES-128 (see the note below). Caskara handles encryption and decryption automatically during I/O.

```
// Call this once during initialization
Caskara.encrypt(SecretConfig.class, "your-super-secret-key");

// From now on, SecretConfig data is stored as encrypted blobs in SQLite
Caskara.save(new SecretConfig("token", "xyz-123"));

// Rotate encryption key seamlessly across all saved data without data loss
Caskara.rotateKey(SecretConfig.class, "your-super-secret-key", "new-stronger-key");
```

***

## ⚙️ Technical Details

### JSON Query Engine

Caskara uses SQLite's `json_extract` to query data without a fixed schema. When you call `createIndex()`, Caskara generates a Computed SQL Index on the JSON property.

```
Caskara.createIndex(Player.class, "stats.level");

// Caskara runs this internally for O(1) lookups:
// CREATE INDEX idx_player_level ON elements(json_extract(json, '$.stats.level'))
```

### Performance Metrics

Caskara tracks everything. Access the `Stats` engine to see how your mod is performing:

```
var stats = Caskara.stats();
System.out.println("Cache Hit Rate: " + stats.getCacheHitRate() * 100 + "%");
System.out.println("Avg Latency: " + stats.getAverageQueryTimeMs() + "ms");
```

***

## 💾 Built-in Auto-Backup

Caskara has a powerful built-in **Auto-Backup System** that automatically backs up all of your databases safely using native SQLite atomic backup APIs. It runs silently in the background every 1 hour.

*   Use `/caskara backup` in-game to trigger an instant global backup.
*   Use `/caskara autobackup <hours>` to change the backup frequency on-the-fly.

> **WARNING — ALWAYS BACK UP YOUR DATABASE FILES!** Although Caskara safely backs up your databases, you should **always** include the `global/` and `worlds/` folders in your own OS-level server backups! A broken hard drive or accidental folder deletion will destroy both your databases and Caskara's automatic `.bak` files. Do your own off-site backups!

***

## 📊 Technical Comparison

| Feature                |Caskara         |Raw SQLite   |MongoDB |
| ---------------------- |--------------- |------------ |------- |
| <strong>NoSQL Flexibility</strong> |✅ (JSON)        |❌ (Rigid)    |✅       |
| <strong>ACID Transactions</strong> |✅ Built-in      |✅ SQL        |✅       |
| <strong>Transparent Encryption</strong> |✅ 1-Line        |❌ Complex    |✅       |
| <strong>In-Memory Caching</strong> |✅ (Dynamic LRU) |❌            |✅       |
| <strong>Async Write Queue</strong> |✅ 100k+ TPS     |❌ Lock-heavy |✅       |
| <strong>Setup Overhead</strong> |Zero            |High         |High    |
| <strong>Auto-Indexing</strong> |✅               |❌            |✅       |

***

## 🛑 Scale and Limitations: When to migrate?

Caskara is engineered to handle **colossal** amounts of data, provided your mod runs on a **Single Hytale Server**. Thanks to its Async Write-Ahead Queue, Caskara can easily absorb 50,000 to 100,000 asynchronous writes per second without blocking the main game thread. However, you must understand its architectural limits:

**Do NOT use Caskara if:**

*   **You are building a Multi-Server Network (BungeeCord/Proxy Style)**: SQLite relies on physical file locks (`.db`). You cannot share a single Caskara database file across multiple physical servers running on different machines. Doing so over a network drive will cause data corruption. _Migration Path: If your mod grows to a multi-server network, you must migrate to a centralized database like MongoDB or MySQL._
*   **You require heavy Relational Joins**: Caskara is a Document Store (NoSQL). While it supports indexing, if your data model requires complex joins across 10+ tables (e.g., highly relational web-app structures), you should use a raw SQL approach.
*   **You are storing BLOBs**: Do not store large images, videos, or schematics inside Caskara. Use Hytale's native asset system or standard flat-file storage for large binary files.

***

## 🔄 Legacy Data Auto-Migration & Safety Backups

### Why the Change?

Historically, older versions of Caskara used a single database named `default.db` by default. If multiple mods used Caskara on the same Hytale server without custom Shell configurations, they all read and wrote to the same file. This caused severe clashing and namespace conflicts.

To fix this, version `2.1.0` introduces **Namespace Isolation** via `Caskara.init("my_mod_id", folder)`. However, if you simply rename the file, you would either break other mods or lose your users' existing data.

### How the Auto-Migration Works (Type Extraction)

Caskara resolves this cleanly and safely using **Type-Based Data Extraction**:

1.  When you initialize your mod with its unique namespace (e.g. `my_mod_id.db`), Caskara detects if the legacy `default.db` still exists in the database directory.
2.  **Safety Backup**: Before touching any data, Caskara automatically duplicates the original legacy database file to `default.db.migration.bak` in the same directory.
3.  **Surgical Extraction**: Instead of moving the whole database (which would steal data belonging to other mods), Caskara opens `default.db`, extracts **only the rows matching the entity classes registered by your mod** (using the `type` column), and moves them into your new, isolated `my_mod_id.db` file.
4.  **Cleanup**: Once copied successfully, it deletes those specific rows from the old `default.db` file.

_Result:_ Your mod gets its isolated database, other legacy mods can still read their own data from `default.db`, and you have a safety backup file (`default.db.migration.bak`) on disk in case you need to rollback.

> **NOTE — Who is affected?** This auto-migration ONLY runs for entities that were previously saved in the default global database (`default.db`). If your entities were configured to use custom databases via `@CaskaraEntity(shell = "my_custom_shell")`, they are already isolated and will not trigger or be affected by this migration.

_Made with ❤️ for the Hytale community._
