package com.cookie.caskara.db;

import com.cookie.caskara.exceptions.DatabaseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * A 'Pearl' represents a single result from a Core.
 * Professional version with exception handling and timeouts.
 */
public class Pearl<T> {
    private final CompletableFuture<T> future;
    private T value;

    public Pearl(T value) {
        this.value = value;
        this.future = CompletableFuture.completedFuture(value);
    }

    public Pearl(CompletableFuture<T> future) {
        this.future = future;
    }

    /**
     * Gets the value synchronously, blocking if necessary.
     * Thrown DatabaseException if the underlying operation failed.
     */
    public Optional<T> sync() {
        if (value != null) return Optional.of(value);
        try {
            // Virtual threads mean blocking is okay, but we use a timeout for safety
            value = future.get(5, TimeUnit.SECONDS);
            return Optional.ofNullable(value);
        } catch (ExecutionException e) {
            throw new DatabaseException("Operation failed inside Pearl retrieval", e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new DatabaseException("Operation timed out or was interrupted", e);
        }
    }

    /**
     * Gets the value asynchronously.
     */
    public CompletableFuture<Optional<T>> async() {
        return future.thenApply(Optional::ofNullable);
    }

    /**
     * Performs an action if the value is present. (Synchronous)
     */
    public void ifFound(Consumer<T> action) {
        sync().ifPresent(action);
    }

    /**
     * Maps the pearl to another type.
     */
    public <R> Pearl<R> map(java.util.function.Function<T, R> mapper) {
        return new Pearl<>(future.thenApply(val -> val != null ? mapper.apply(val) : null));
    }
}
