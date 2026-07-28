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

    /**
     * Default blocking timeout for {@link #sync()}.
     * <p>
     * A hard 5s was previously baked in, so a legitimate read taking longer than that
     * (behind a VACUUM, a big backup or a busy write lock) failed instead of waiting.
     * Adjust globally with {@link #setDefaultTimeout(long, TimeUnit)} or per call with
     * {@link #sync(long, TimeUnit)}.
     */
    private static volatile long defaultTimeoutMillis = 5_000L;

    /** Sets the global default timeout used by {@link #sync()}. Must be positive. */
    public static void setDefaultTimeout(long timeout, TimeUnit unit) {
        if (timeout <= 0) {
            throw new IllegalArgumentException("Pearl timeout must be positive, got: " + timeout);
        }
        defaultTimeoutMillis = unit.toMillis(timeout);
    }

    public static long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

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
        return sync(defaultTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Gets the value synchronously, blocking up to the given timeout.
     */
    public Optional<T> sync(long timeout, TimeUnit unit) {
        if (value != null) return Optional.of(value);
        try {
            // Virtual threads mean blocking is okay, but we use a timeout for safety
            value = future.get(timeout, unit);
            return Optional.ofNullable(value);
        } catch (ExecutionException e) {
            throw new DatabaseException("Operation failed inside Pearl retrieval", e.getCause());
        } catch (TimeoutException e) {
            throw new DatabaseException("Operation timed out after " + unit.toMillis(timeout)
                    + "ms. Raise it with Pearl.setDefaultTimeout(...) if this is expected.", e);
        } catch (InterruptedException e) {
            // Restore the flag so callers up the stack can still observe the interruption.
            Thread.currentThread().interrupt();
            throw new DatabaseException("Operation was interrupted", e);
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
