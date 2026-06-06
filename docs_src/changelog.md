# Caskara Changelog

## [2.0.0] - The Hardening Update

### ✨ Features
* **In-Game Command Suite**: Added the comprehensive `/caskara` command for database administration directly within Hytale.
  * `/caskara stats`: View total databases, cores, memory footprint, hit rates, and disk sizes.
  * `/caskara vacuum`: Manually trigger a global SQL VACUUM on all connected database shells.
  * `/caskara backup`: Instantly perform an atomic, thread-safe backup of all databases.
  * `/caskara autobackup <hours>`: Adjust or disable the Auto-Backup interval on the fly.
  * `/caskara dump <package_name>`: Export database contents to disk for analysis.
  * `/caskara scan <package_name>`: Manually scan packages for `@CaskaraEntity` definitions.
* **Native Atomic Auto-Backups**: Caskara now has a built-in background scheduler that safely backs up all active SQLite databases without causing locks or database corruption (properly handles SQLite WAL mode using native APIs). Enabled by default every 1 hour.
* **Auto-Vacuum Scheduler**: Implemented a global background daemon to automatically VACUUM databases (every 12 hours by default) to keep storage footprint small.
* **Strict Code Style Constraints**: Implemented an AI agent skill rule to definitively eliminate inline fully qualified class names, ensuring clean, readable Java imports.

### 🐛 Bug Fixes
* **SQL DDL Parameterization Crash**: Fixed an issue where the `@Index` and `@Indices` annotations used `PreparedStatement` parameter bindings (`?`) for creating indexes, which SQLite's schema definitions (DDL) natively block, resulting in instant startup crashes.
* **Database Connection Memory Leak**: Repaired a race condition in `Shell.java`'s initialization flow. Simultaneous multi-thread requests during boot could bypass locks, creating duplicate SQLite connections and duplicate internal TTL memory cleanup tasks. Mitigated with Double-Check Locking.
* **Stranded WAL Files on Shutdown**: The `Caskara.shutdown()` hook neglected to invoke `close()` on the active database Shells. Abrupt Hytale server shutdowns previously abandoned in-memory SQLite `.db-wal` temporary files without checkpointing them to disk, risking data rollback.
* **Server-Wide Concurrency Crash**: Fixed a critical structural flaw where internal caches (`Caskara.shells` and `Shell.cores`) used standard `HashMap`s. Heavy, simultaneous data operations across multiple plugins previously threw `ConcurrentModificationException`, crashing the entire server database layer. Resolved by migrating to `ConcurrentHashMap`.
* **Hot-Reload Scheduler Failure**: Fixed an issue where executing `Caskara.shutdown()` would kill the background executor service but leave the reference dangling. Subsequent attempts to reload or restart Caskara operations (like hot-reloading) instantly crashed via `RejectedExecutionException`.

### 📖 Documentation & Localization
* **README Backup Warning**: Added explicit warnings advising server owners that OS-level backups are still required for catastrophic hardware failures, despite Caskara's automatic backups.
* **English Translation**: Completely localized the codebase, translating all remaining internal developer comments into English.
