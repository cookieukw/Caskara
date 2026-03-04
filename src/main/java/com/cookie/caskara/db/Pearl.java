package com.cookie.caskara.db;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A 'Pearl' represents a single result from a Core.
 * It encapsulates the value and allows for both synchronous and asynchronous access.
 */
public class Pearl<T> {
    private final T value;
    private final CompletableFuture<T> future;

    public Pearl(T value) {
        this.value = value;
        this.future = CompletableFuture.completedFuture(value);
    }

    public Pearl(CompletableFuture<T> future) {
        this.value = null; // Value is only available via sync() or async()
        this.future = future;
    }

    /**
     * Gets the value synchronously, blocking if necessary.
     */
    public Optional<T> sync() {
        try {
            return Optional.ofNullable(future.get());
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Gets the value asynchronously.
     */
    public CompletableFuture<Optional<T>> async() {
        return future.thenApply(Optional::ofNullable);
    }

    /**
     * Performs an action if the value is present (asynchronously).
     */
    public void ifFound(Consumer<T> action) {
        future.thenAccept(val -> {
            if (val != null) action.accept(val);
        });
    }

    /**
     * Maps the pearl to another type.
     */
    public <R> Pearl<R> map(java.util.function.Function<T, R> mapper) {
        return new Pearl<>(future.thenApply(val -> val != null ? mapper.apply(val) : null));
    }
}
