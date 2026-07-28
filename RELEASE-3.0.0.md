# Caskara 3.0.0 — Audit & Hardening

**Release date:** 2026-07-28
**Previous release:** 2.1.0

This release is the result of a full audit of the codebase. It is a major version because it
changes the on-disk schema, alters two API signatures and corrects a behaviour that some
entities may have depended on.

Most of what follows are **silent data-loss bugs** — failures that produced no exception, no
log line and no visible symptom other than data quietly disappearing. If you run Caskara on a
live server, read [Upgrade Guide](#upgrade-guide) before deploying.

---

## Table of contents

- [Why 3.0.0](#why-300)
- [Upgrade Guide](#upgrade-guide)
- [Critical Fixes — silent data loss](#critical-fixes--silent-data-loss)
- [Concurrency & Correctness Fixes](#concurrency--correctness-fixes)
- [Security](#security)
- [New Features](#new-features)
- [Performance & Internals](#performance--internals)
- [Admin UI & Commands](#admin-ui--commands)
- [Build & Repository](#build--repository)
- [Documentation](#documentation)
- [Known Limitations](#known-limitations)
- [Verification](#verification)

---

## Why 3.0.0

Four changes break backward compatibility:

| Change | Impact |
|---|---|
| `elements` table rebuilt with `PRIMARY KEY (id, type)` | On-disk schema; migrated automatically on first open |
| `CaskaraAdminLogic.deleteEntity()` gained a third parameter | Source-incompatible |
| `BackupManager` gained a retention constructor parameter | Source-compatible (old constructor kept) |
| `@Id` now always takes precedence over the `id`/`uuid`/`uid` naming convention | Behavioural |

---

## Upgrade Guide

### 1. Back up your world

The migration takes its own snapshot, but take yours too.

### 2. The database migrates itself on first open

When a shell opens for the first time under 3.0.0, Caskara rebuilds the `elements` table with a
composite primary key. The sequence is:

1. Read `PRAGMA user_version`. If it is already `1`, return immediately — the migration is
   idempotent and runs exactly once per file.
2. If the table already has a composite key (a database created fresh under 3.0.0), just stamp
   the version.
3. Write a consistent snapshot via `VACUUM INTO` to `<shell>.db.pre-composite-key.bak`.
   **If the snapshot cannot be written, the migration aborts and your database is left
   untouched.**
4. Inside a single transaction: drop the FTS triggers and shadow table, create the new table,
   copy every row, drop the old table, rename, recreate `idx_type`, set `user_version = 1`.
5. On any error, roll back. The snapshot stays on disk.

The copy cannot lose rows: the old primary key already guaranteed ids were unique, so no
`(id, type)` pair can collide. Rows predating the `type` column are migrated with an empty type.

> **What this migration cannot do:** if two entity types already collided in your database
> before upgrading, the overwritten record is gone. The migration prevents future collisions;
> it cannot recover data that was already lost.

### 3. Re-issue programmatic indexes

Indexes created at runtime through `Caskara.createIndex(clazz, field)` live on the old table and
are dropped by the rebuild. Indexes declared with `@Index` are recreated automatically the next
time the Core is constructed. Purely programmatic ones must be re-issued after startup.

### 4. Update `deleteEntity()` call sites

```java
// Before
CaskaraAdminLogic.deleteEntity(shellName, id);

// After — the type is required, since one id can now map to several entity types
CaskaraAdminLogic.deleteEntity(shellName, id, type);
```

### 5. Check entities that declare both `@Id` and a field named `id`

`@Id` now wins unconditionally. Previously `getId()` and `syncId()` disagreed with each other:
when the `@Id` field was null, `getId()` fell through to the naming convention while `syncId()`
stopped at `@Id`. If you have an entity with `@Id` on one field *and* another field called
`id`/`uuid`/`uid`, verify which one you actually want as the key.

### 6. `TTL` semantics are now what the docs always said

If you called `Caskara.save(obj, 5000)` expecting "expires in 5 seconds", that is what happens
now. Previously it meant "expires at epoch 5000" — i.e. 1970 — and the record was invisible from
the moment it was written. **Any code that appeared to work around this may now behave
differently**, because records actually persist.

### 7. Builds no longer auto-deploy

`./gradlew shadowJar` no longer copies the jar into a local Hytale install. Use
`./gradlew shadowJar -Pdeploy`, or run `./gradlew deploy` directly.

---

## Critical Fixes — silent data loss

### `save(obj, ttlMillis)` made every record expire in 1970

`Caskara.save(T, long)` passed its argument straight to `preserve(id, obj, expiresAtMillis)`,
whose third parameter is an **absolute epoch timestamp**, not a duration. `save(obj, 5000)`
therefore stamped the record as expiring at epoch 5000. Every read filters on
`expires_at > now`, so the record vanished immediately — no error, no log.

`save(T, Duration)` and `saveAsync(T, long)` always computed `now + millis` correctly; only this
overload was wrong, and `DOCS.md` had documented the intended behaviour all along.

### Entities of different types overwrote each other

The schema declared `id TEXT PRIMARY KEY` with `type` as an ordinary column. Since every write is
an `INSERT OR REPLACE`, saving a `Player` with id `"steve"` and then an `Inventory` with id
`"steve"` **destroyed the Player**. All reads filter on `id AND type`, so it simply disappeared.

This affected any mod using natural ids — a player name or UUID — across more than one entity
type in the same shell, which is the most common usage there is.

Fixed by rebuilding the table with `PRIMARY KEY (id, type)`.

### A bare `@TTL` discarded every record

`@TTL` declares `minutes()` and `seconds()`, both defaulting to `0`. An unqualified `@TTL` gave
`defaultTtlMillis = 0`, so `expiresAt = now + 0` — expired at the instant of writing. A bare
`@TTL` is now ignored with a warning.

### Schema migrations wrote plaintext for `@Encrypted` entities

After running the registered migrators, `applyMigrations()` persisted the result with
`setString(1, finalJson)` — without re-encrypting. Reading a single outdated `@Encrypted` record
rewrote it to disk in the clear, and left it unreadable on the next load.

### Key rotation could make data permanently unreadable

`rotateKey()` read everything with the old key and rewrote it with the new one. But
`extractAll()` silently drops records it cannot decrypt, so any record that failed was never
rewritten — and once the key changed, it was unrecoverable.

`rotateKey()` now compares a raw SQL `count()` against the number of successfully decrypted
records and **aborts before changing the key** if they differ, restoring the old key.

### Export/import silently discarded TTL, soft-deletes and versions

`exportToJson()` carried only `id`, `type` and `json`. A round-trip dropped every TTL,
resurrected every soft-deleted record and reset the schema version to 1. All six columns now
travel. `importFromJson()` also replaced a raw `List.class` token with a typed `TypeToken` — the
old unchecked cast to `Map<String,String>` threw as soon as a numeric column was present, since
Gson returns `Map<String,Object>` with numbers as `Double`.

### `@Id` inherited from a base class was ignored

`syncId()` and `Caskara.getId()` both called `getDeclaredFields()` on the concrete class only. An
entity inheriting its `@Id` from a base class was never recognised: the id was not injected, and
`save(obj)` generated a fresh UUID on **every call**, creating a duplicate record per save.

Both now share `Core.findIdField()`, which walks the class hierarchy.

---

## Concurrency & Correctness Fixes

### Reading inside a transaction always failed

`Core.extract()` dispatched every read to another thread via
`CompletableFuture.supplyAsync(() -> shell.runInLock(...))`. But `Shell.transaction()` holds the
`ReentrantLock` **on the calling thread**, which then blocked in `Pearl.sync()` waiting for a
thread trying to acquire that same lock. Guaranteed deadlock until the 5-second timeout fired and
threw.

In other words, `tx.load()` never worked — including the exact pattern the README documents in
its ACID Transactions example. No test covered reads inside a transaction.

`extract()` now detects `shell.isLockHeldByCurrentThread()` and reads inline on the current
thread; the lock is reentrant, so this is safe.

### Nested transactions committed early

`Shell.transaction()` called `setAutoCommit(false)` and `commit()` without knowing it was already
inside another transaction. The inner one committed the outer one's work; if the outer later
failed, the rollback undid nothing.

This was reachable in practice: `Caskara.saveAll()` opens a transaction → `tx.save()` →
`shell.core()` → the `Core` constructor → `initializeFts()` / `autoMigrateLegacyData()`, both of
which call `shell.transaction()`.

Now tracked with a nesting counter; an inner transaction joins the outer one.

### `Shell.core()` could throw or deadlock

The `Core` constructor runs DDL, opens connections, executes transactions and may re-enter
`shell.core()`. Doing that inside `ConcurrentHashMap.computeIfAbsent` risks an
`IllegalStateException` ("recursive update") or a blocked map bin. Replaced with double-checked
locking on a dedicated monitor.

### Stale FTS5 search results

Every write uses `INSERT OR REPLACE`. In SQLite, REPLACE deletes the old row **without firing
`AFTER DELETE` triggers** unless `recursive_triggers` is enabled — and the rowid changes — so the
`fts_elements` index accumulated orphaned entries forever, returning duplicates and stale
document versions from `search()`.

Fixed with `PRAGMA recursive_triggers = ON`.

### `connection` was not `volatile`

Written by `initConnection()` and read by the async writer thread, the cleanup scheduler and the
virtual-thread executor — some outside the lock, since `getConnection()` does not lock. Without a
memory barrier a thread could observe `null` or a partially initialised connection.

### Scheduler leak on every reconnect

`initConnection()` called `startCleanupTask()` unconditionally, and `getConnection()` calls
`initConnection()` whenever the connection is closed. Each reconnect spawned another cleanup
thread, all running the same `DELETE`, forever. `Shell.close()` also now shuts down the virtual
thread executor and clears caches.

### Query results skipped `@Id` sync and schema migrations

The SQL path deserialised with `Gson.fromJson(...)` directly, bypassing the `syncId()` and
`applyMigrations()` that `extract()` and `extractAll()` apply. Objects returned by
`query().fetch()` had **empty id fields**, and outdated records came back unmigrated. Results now
go through `Core.materialize()`, the same path as `extract()`.

### Numeric equality never matched on encrypted cores

Encrypted cores fall back to in-memory filtering, where `getFieldValue()` normalises every number
to `Double`. `actual.equals(target)` then compared `Double(5.0)` against `Integer(5)` and returned
false, so `field("level", 5)` never matched anything on an `@Encrypted` entity. Same for `IN`.
Replaced with a numeric-tolerant `equalsValue()`.

### Nested field paths only worked in SQL

The SQL path uses `json_extract(json, '$.' || fieldName)`, which understands `"location.x"`. The
in-memory fallback used `json.get(fieldName)`, returning null for any dotted path — so the same
filter gave different results depending on whether the entity was encrypted. The in-memory path
now walks dotted paths too.

### Other correctness fixes

- `page(0, n)` produced a negative `OFFSET`, which SQLite rejects. Clamped to zero.
- `@Cache(maxSize = 0)` produced a cache that evicted everything; a negative value threw
  `IllegalArgumentException` from the `Core` constructor. Clamped to a minimum of 1.
- The admin UI deleted rows without invalidating the Core LRU cache, so `extract()` kept serving
  deleted entities from memory. Deletion now runs under the shell lock and invalidates caches.
- Admin entity listing paginated with `LIMIT`/`OFFSET` and no `ORDER BY`, so pages could repeat
  or skip rows. Now ordered by `(type, id)`.
- `VACUUM` ran outside the shell lock, racing with concurrent writers.
- Using the API before `Caskara.init()` resolved shells against a relative path silently. It now
  throws `IllegalStateException` with an actionable message.
- `enableAutoVacuum()` and `enableAutoBackup()` each lazily created their own scheduler, so
  whichever ran first won and the other's thread name was never used. `shutdown()` also left
  stale `ScheduledFuture` references behind.

---

## Security

### SQL injection in `createIndex()`

`Core.createIndex(String jsonField)` is public — reachable through
`Caskara.createIndex(clazz, field)` — and interpolated its argument directly into DDL:

```java
"CREATE INDEX IF NOT EXISTS " + indexName +
" ON elements(json_extract(json, '$." + jsonField + "'))..."
```

SQLite cannot bind identifiers or JSON paths, so the surrounding `PreparedStatement` offered no
protection. A field name originating from config or a command could inject arbitrary SQL. The
argument is now validated against a strict identifier pattern.

### Hardcoded encryption key removed from the shipped plugin

`MainPlugin.setup()` ran a `testAdvancedFeatures()` demo routine **on every server start**: it
created `PlayerData` records, registered observers and called
`Caskara.encrypt(PlayerData.class, "hytale-secure-key-123")` — a hardcoded key compiled into the
published jar. It also used `Caskara.init(File)`, the overload the project itself deprecates for
causing `default.db` collisions.

The demo is gone; `setup()` now only initialises the engine (namespaced as `"caskara"`) and
registers commands.

### Encryption is AES-128/ECB, not AES-256

The README, CurseForge page and DOCS headings advertised **AES-256**, while the DOCS API tables
said AES-128 and the code implemented AES-128. `Cipher.getInstance("AES")` resolves to
**AES-128/ECB/PKCS5Padding**:

- ECB with no IV — identical plaintexts produce identical ciphertexts, so anyone with file access
  can tell which records are equal;
- no authentication tag — the ciphertext is malleable, with no integrity check;
- key derivation is a single unsalted SHA-256 truncated to 16 bytes, with no KDF stretching.

**The algorithm was not changed in this release** — doing so would render all existing encrypted
data unreadable. The documentation was corrected and a threat model added to `DOCS.md`. Treat it
as obfuscation for tokens inside a file admins already control, not as at-rest encryption for
highly sensitive data. See [Known Limitations](#known-limitations).

---

## New Features

### Query builder

```java
// New terminal operations
long total    = Caskara.query(Player.class).fieldGreaterOrEqual("level", 50).count();
boolean any   = Caskara.query(Player.class).field("rank", "admin").exists();
int purged    = Caskara.query(Session.class).fieldLessThan("lastSeen", cutoff).delete();

// New operators
.fieldNotEquals("banned", true)
.fieldGreaterOrEqual("level", 50)
.fieldLessOrEqual("level", 99)
```

- `count()` deliberately ignores `limit`/`offset`, so it answers "how many pages?" rather than
  "how many on this page?".
- `exists()` short-circuits with `LIMIT 1`.
- `delete()` runs as a single statement and returns the number of rows removed, then invalidates
  caches since the affected ids are not known up front.
- All of these work on encrypted cores too, via the in-memory path.

`fieldNotEquals()` carries an explicit `IS NOT NULL` guard. Without it, `json_extract()` returns
NULL for an absent field and `NULL IS NOT 10` is true, so a record missing the field would match
in SQL but not in memory — the same SQL-versus-memory divergence this release fixes elsewhere.

### Observer lifecycle

```java
core.observeAll((id, player) -> {
    if (player == null) cache.evict(id);   // null now means "deleted"
    else                cache.put(id, player);
});

core.onAfterDelete(id -> audit.log("removed " + id));

core.observe(playerId, watcher);
core.unobserveAll(playerId);               // on disconnect
```

Previously only `preserve()` emitted events, so anything using `observeAll()` to mirror state
never learned about deletions. And subscriptions could not be cancelled at all — the listener map
only ever grew, which leaks on a server registering one observer per joining player.

New: `onAfterDelete()`, `unobserve(id, observer)`, `unobserveAll(id)`,
`unobserveAll(observer)`, `getObservedIdCount()`.

### Other additions

| API | Description |
|---|---|
| `Core.count()` | Counts live records without deserialising or decrypting |
| `Caskara.globalStats()` | Aggregates metrics across every open shell |
| `Stats.merge(Stats)` | Combines counters; backs `globalStats()` |
| `Pearl.sync(timeout, unit)` | Per-call blocking timeout |
| `Pearl.setDefaultTimeout(t, unit)` | Replaces the hardcoded 5s default |
| `Shell.invalidateCaches()` | Drops every Core cache after out-of-band writes |
| `Shell.isLockHeldByCurrentThread()` | Lets callers avoid cross-thread dispatch |
| `Core.findIdField(Class)` | Shared `@Id` resolver, hierarchy-aware |
| `CaskaraAdminLogic.listShellFileNames()` | Enumerates open shells |

### Backup rotation

`BackupManager` keeps the **48 most recent** backups per shell, configurable through a new
constructor parameter (`0` disables pruning). Previously nothing was ever pruned: at the default
hourly cadence that is 24 files per shell per day, forever — the repository itself had
accumulated 11 stale ones.

Pruning sorts on the embedded `yyyyMMdd_HHmmss` stamp, which is lexicographically chronological,
using `lastModified` only to break ties.

Single quotes in the destination path are now escaped — `backup to '<path>'` previously broke on
any path containing an apostrophe.

---

## Performance & Internals

- **`CaskaraLogger` no longer reflects on every call.** `Class.forName` + `getMethod` + `invoke`
  ran on *every* `info`/`warn`/`error`, including when the Hytale logger was not present at all.
  The lookup result (including "unavailable") is now resolved once and cached.
- `Pearl.sync()` restores the thread's interrupt flag on `InterruptedException`, so callers up
  the stack can still observe it.
- `Shell.close()` shuts down the virtual-thread executor and clears caches instead of leaving
  them running.
- `Core.extract()` falls back to an inline read when the executor is shut down, rather than
  throwing `RejectedExecutionException`.

---

## Admin UI & Commands

- **The admin UI pointed at shells that do not exist.** `global.db`, `players.db`, `quests.db`
  and `economy.db` were hardcoded, and the page opened on `global.db`. None of these exist in a
  normal install — the default shell is named `<modId>.db` — so the UI opened empty every time.
  Sidebar slots are now populated from the shells actually open, with unused slots hidden.
- The shell switch handler matched on raw substrings (`eventData.contains("global.db")`); it now
  dispatches on a slot index.
- `dumpEntity` iterates every row for an id instead of reading only the first, since one id can
  now legitimately map to several types.
- `getGlobalStatsMap()` reported the size of `.db` files on disk under the label `"Memory"`. It
  now publishes `"Disk"`, keeping `"Memory"` as an alias so existing bindings keep working.
- Debug `System.out.println` spam removed from the entity listing path; errors route through
  `CaskaraLogger`.

---

## Build & Repository

- `tasks.shadowJar.finalizedBy('deploy')` ran after **every** build, copying the jar into a local
  Hytale installation. This broke CI and any machine without one. Deploy is now opt-in via
  `-Pdeploy`.
- `.gitignore` extended to cover `local.properties`, `*.db`, `*.db-wal`, `*.db-shm`, `*.bak` and
  `test_admin_logic_db/`.
- Untracked from the index (files preserved on disk): the test database and its 11 backups, and
  `local.properties`, which contained machine-specific paths.

---

## Documentation

- `README.md`: new Querying and Reactive Observers sections, `globalStats()` alongside `stats()`,
  full changelog and upgrade notes.
- `CURSEFORGE.md`: "What's New" block and matching changelog.
- `DOCS.md`: new Query operations, Core observer-lifecycle methods and `onAfterDelete()` added to
  the API reference; AES threat model added.
- `AUDIT.md`: the complete audit — every finding, its reasoning, and what was done about it.

---

## Known Limitations

Three findings from the audit were deliberately **not** changed in this release, because each
requires a product decision rather than a technical one.

### Encryption remains AES-128/ECB

Migrating to AES-GCM with a random per-record IV and PBKDF2/Argon2 derivation is the correct fix,
but it would make all existing encrypted data unreadable. The safe path is a versioned ciphertext
envelope (`v2:<iv>:<ct>`) that reads the legacy format and rewrites lazily. Deferred to a future
release.

### `decrypt()` returns garbage instead of failing

On any failure it returns the input unchanged, on the assumption it might be legacy unencrypted
data. A wrong key therefore surfaces as "record not found" — indistinguishable from an id that
genuinely does not exist. Distinguishing the two needs a heuristic that does not break the
intentional legacy-data fallback.

### `autoMigrateLegacyData()` still runs in the `Core` constructor

It opens a **second JDBC connection** to `default.db` and issues a `DELETE` there, potentially
while Caskara holds that same file open with WAL enabled — two connections writing to one file,
one of them outside the shell lock. The right fix is to move it out of the constructor into an
explicit `Caskara.migrateLegacy()`, but that changes behaviour for anyone relying on the
automatic migration.

### Test coverage gaps

The following are not yet covered, and each corresponds to a bug fixed in this release that would
otherwise regress silently:

- reading inside a transaction (`tx.load`)
- nested transactions
- `save(obj, ttlMillis)` verifying the record still exists afterwards
- a bare `@TTL`
- `query().fetch()` returning populated `@Id` fields
- numeric `field(name, value)` filters on `@Encrypted` entities
- schema migration over an `@Encrypted` entity
- id collision between two types in one shell
- `@Id` inherited from a base class
- export/import round-trip preserving TTL and soft-delete state

---

## Verification

- The full test suite compiles and passes.
- The composite-key migration was executed against a real legacy database (18 records): schema
  converted, `user_version` stamped, all 18 rows identical before and after, second run correctly
  skipped, `type` NULL rejected, and the collision scenario (`steve/player` + `steve/inventory`)
  confirmed to coexist rather than overwrite.
- The new `Query` SQL (`count`, `exists`, `delete`, and each new operator) was executed directly
  against SQLite, including the `fieldNotEquals` NULL-handling edge case.
