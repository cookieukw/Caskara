package com.cookie.caskara;

import com.cookie.caskara.db.Pearl;
import com.cookie.caskara.exceptions.DatabaseException;
import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Pearl result wrapper: sync, async, ifFound, and map.
 */
class PearlTest {

    @Test
    @DisplayName("Pearl(value).sync() returns Optional.of the value")
    void testSyncWithValue() {
        Pearl<String> pearl = new Pearl<>("hello");
        Optional<String> result = pearl.sync();
        assertTrue(result.isPresent());
        assertEquals("hello", result.get());
    }

    @Test
    @DisplayName("Pearl(null value).sync() returns Optional.empty()")
    void testSyncWithNull() {
        Pearl<String> pearl = new Pearl<>((String) null);
        Optional<String> result = pearl.sync();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Pearl(future).sync() blocks and returns result")
    void testSyncWithFuture() {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "async-value");
        Pearl<String> pearl = new Pearl<>(future);
        assertEquals("async-value", pearl.sync().orElse(null));
    }

    @Test
    @DisplayName("Pearl.async() returns a CompletableFuture<Optional<T>>")
    void testAsync() throws Exception {
        Pearl<String> pearl = new Pearl<>("value");
        Optional<String> result = pearl.async().get();
        assertTrue(result.isPresent());
        assertEquals("value", result.get());
    }

    @Test
    @DisplayName("Pearl.ifFound() executes the consumer when value is present")
    void testIfFound() {
        Pearl<String> pearl = new Pearl<>("present");
        boolean[] called = {false};
        pearl.ifFound(v -> called[0] = true);
        assertTrue(called[0]);
    }

    @Test
    @DisplayName("Pearl.ifFound() is NOT called when value is absent")
    void testIfFoundAbsent() {
        Pearl<String> pearl = new Pearl<>((String) null);
        boolean[] called = {false};
        pearl.ifFound(v -> called[0] = true);
        assertFalse(called[0]);
    }

    @Test
    @DisplayName("Pearl.map() transforms the wrapped value")
    void testMap() {
        Pearl<String> pearl = new Pearl<>("42");
        Pearl<Integer> mapped = pearl.map(Integer::parseInt);
        Integer result = mapped.sync().orElse(null);
        assertNotNull(result);
        assertEquals(42, result);
    }

    @Test
    @DisplayName("Pearl.sync() throws DatabaseException on future failure")
    void testSyncThrowsOnFailure() {
        CompletableFuture<String> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("boom"));
        Pearl<String> pearl = new Pearl<>(failedFuture);
        assertThrows(DatabaseException.class, pearl::sync);
    }
}
