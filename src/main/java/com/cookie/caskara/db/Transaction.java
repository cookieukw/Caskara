package com.cookie.caskara.db;

/**
 * A 'Transaction' allows grouping multiple database operations into a single atomic block.
 * "All or nothing" persistence.
 */
public class Transaction {
    private final Shell shell;

    public Transaction(Shell shell) {
        this.shell = shell;
    }

    /**
     * Preserves an object within this transaction.
     */
    @SuppressWarnings("unchecked")
    public <T> String save(T object) {
        return shell.core((Class<T>) object.getClass()).preserve(object);
    }

    /**
     * Preserves an object with a specific ID within this transaction.
     */
    @SuppressWarnings("unchecked")
    public <T> String save(String id, T object) {
        return shell.core((Class<T>) object.getClass()).preserve(id, object);
    }

    /**
     * Discards an object within this transaction.
     */
    public <T> void delete(String id, Class<T> clazz) {
        shell.core(clazz).discard(id);
    }

    /**
     * Loads an object by ID within this transaction.
     */
    public <T> T load(String id, Class<T> clazz) {
        return shell.core(clazz).extract(id).sync().orElse(null);
    }
}
