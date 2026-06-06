# Caskara API: Technical Reference

This document provides a comprehensive breakdown of the Caskara API, including method signatures, parameters, return types, and behavioral notes.

---

## 1. `Caskara` (Main Entry Point)

The `Caskara` class is the primary static entry point for the API. It manages global shells and provides high-level static methods for common operations.

| Method | Parameters | Return Type | Description |
| :--- | :--- | :--- | :--- |
| `init(File folder)` | `folder`: Root directory for databases. | `void` | Initializes the API. Must be called once before any other operation. |
| `shell(String name)` | `name`: Filename (e.g., "players"). | `Shell` | Opens or retrieves a specific database Shell. |
| `save(T object)` | `object`: The entity to persist. | `String` | Saves an object with an auto-UUID. Returns the assigned ID. |
| `save(T object, Duration ttl)` | `object`, `ttl`: Expiration time. | `String` | Saves an object that will be deleted after the TTL expires. |
| `load(String id, Class<T> clazz)` | `id`, `clazz`: Entity type. | `T` | Synchronously loads an object by ID. Returns `null` if not found. |
| `loadAsync(id, clazz)`| `id`, `clazz` | `CompletableFuture<T>` | Returns a non-blocking future for the entity. |
| `list(clazz)` | `clazz`: The class. | `List<T>` | Synchronously lists all objects of this type. |
| `delete(id, clazz)` | `id`, `clazz` | `void` | Deletes an object physically from the shell. |
| `query(clazz)` | `clazz`: The class. | `Query<T>` | Returns a new Query Builder. |
| `transaction(action)` | `action`: Lambda. | `void` | Executes logic within an ACID transaction. |
| `stats()` | None | `Stats` | Returns performance metrics. |
| `createIndex(clazz, field)`| `clazz`, `field` | `void` | Enables high-performance search for a JSON field. |
| `enableAutoBackup(mins)` | `mins`: Interval. | `void` | Starts a scheduled backup worker. |
| `exportShell(file)` | `file`: JSON target. | `void` | Dumps entire database to a JSON file. |
| `importShell(file)` | `file`: JSON source. | `void` | Bulk imports data from a JSON file. |
| `getId(object)` | `object`: Java entity. | `String` | Reflectively retrieves the ID/UUID from an object. |

---

## 2. `Core<T>` (Collection Management)

A `Core` represents a specific "table" or "collection" of a single type.

| Method | Parameters | Return Type | Description |
| :--- | :--- | :--- | :--- |
| `onBeforeSave(BiConsumer<String, T> hook)`| `hook`: (id, element) -> void | `void` | Triggers before persistence. Use for manual validation or logging. |
| `addValidator(Predicate<T> validator)` | `validator`: Testing lambda. | `void` | If the predicate returns `false`, `preserve()` will throw `ValidationException`. |
| `observe(String id, BiConsumer<String, T> listener)` | `id`, `listener` | `void` | Triggers the listener whenever the specific ID is updated or created. |
| `observeAll(BiConsumer<String, T> listener)` | `listener` | `void` | Subscribes to changes for ANY record of this type. |
| `softDelete(String id)` | `id`: Identifier. | `void` | Marks the record as deleted without purging it. |
| `restore(String id)` | `id`: Identifier. | `void` | Unmarks a soft-deleted record. |
| `createIndex(String jsonField)` | `jsonField`: e.g. "player.level" | `void` | Generates a computed SQL index for high-speed queries. |

---

## 3. `Query<T>` (Query Builder)

Fluent API for filtering and retrieving data from a Core.

| Method | Parameters | Return Type | Description |
| :--- | :--- | :--- | :--- |
| `field(String name, Object value)` | `name`, `value` | `Query<T>` | Filters where JSON `field == value`. |
| `fieldGreaterThan(String name, Object value)` | `name`, `value` | `Query<T>` | Filters where JSON `field > value`. |
| `fieldIn(String name, List<Object> values)` | `name`, `values` | `Query<T>` | Filters where `field` matches any value in the list. |
| `fieldContains(String name, String text)` | `name`, `text` | `Query<T>` | SQL `LIKE` search for strings. |
| `orderBy(String field, Order direction)` | `field`, `Order.ASC/DESC` | `Query<T>` | Sorts results by a specific JSON field. |
| `page(int page, int size)` | `page`, `size` | `Query<T>` | Implements pagination (starts at page 1). |
| `fetch()` | None | `List<T>` | Synchronously executes the search and returns results. |
| `fetchAsync()` | None | `CompletableFuture<List<T>>` | Non-blocking execution. |

---

## 4. `Transaction` (Atomic Operations)

Passed to the lambda in `Caskara.transaction()`. If any operation fails or an exception is thrown inside the lambda, **all changes are reverted**.

| Method | Parameters | Return Type | Description |
| :--- | :--- | :--- | :--- |
| `save(T object)` | `object` | `String` | Atomically adds a save operation to the batch. |
| `delete(String id, Class<T> clazz)` | `id`, `clazz` | `void` | Atomically adds a delete operation to the batch. |
| `load(String id, Class<T> clazz)` | `id`, `clazz` | `T` | Loads an entity within the context of the transaction lock. |

---

## 5. `Pearl<T>` (Result Wrapper)

Technical wrapper for asynchronous results, providing a clean bridge between `CompletableFuture` and synchronous code.

| Method | Parameters | Return Type | Description |
| :--- | :--- | :--- | :--- |
| `sync()` | None | `Optional<T>` | Blocks until result is ready (5s timeout). |
| `async()` | None | `CompletableFuture<Optional<T>>` | Non-blocking retrieval. |
| `ifFound(action)`| `Consumer<T>` | `void` | Executes action synchronously if object is found. |
| `map(mapper)` | `Function<T, R>` | `Pearl<R>` | Transforms the result to another type. |

---

## 6. `Stats` (Observability)

Accessible via `Caskara.stats()`. Use this to profile your mod's data footprint.

| Method | Return Type | Description |
| :--- | :--- | :--- |
| `getCacheHitRate()` | `double` | Percent of requests served via RAM (LRU Cache). |
| `getAverageQueryTimeMs()` | `double` | Mean latency for SQL execution in milliseconds. |
| `getTotalQueries()` | `long` | Count of all fetch operations since boot. |

---

## 🛡️ Exception Hierarchy

Caskara uses unchecked exceptions to keep your code clean, but you should handle them in critical areas.

1.  **`CaskaraException`**: Base class for all API errors.
2.  **`DatabaseException`**: Thrown for SQL errors, disk I/O failures, or transaction rollbacks.
3.  **`ValidationException`**: Thrown when a `Core` validator rejects a save operation.

---

## 💎 Data Types & Best Practices

### Supported Types
Caskara uses **Gson** for serialization.
- **Primitives**: int, double, boolean, long, etc.
- **Collections**: List, Map, Set (handled automatically).
- **Custom Objects**: Nested objects are serialized as JSON sub-trees.
- **Transients**: Fields marked with `transient` are ignored.

### Primary Keys (IDs)
Caskara looks for fields named `id`, `uuid`, or `uid` to use as identifiers.
- **Recommendation**: Always include a `public String id;` field in your models.

### Threading & Concurrency
- **Shells** are thread-safe (backed by a `ReentrantLock`).
- **Best Practice**: Use `Caskara.transaction()` for multi-step logic to prevent race conditions.
