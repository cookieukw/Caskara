package com.cookie.caskara;

import com.cookie.caskara.db.Stats;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Stats observability class.
 */
class StatsTest {

    private Stats stats;

    @BeforeEach
    void setup() {
        stats = new Stats();
    }

    @Test
    @DisplayName("Initial cache hit rate is 0.0 (no requests yet)")
    void testInitialCacheHitRate() {
        assertEquals(0.0, stats.getCacheHitRate());
    }

    @Test
    @DisplayName("100% hit rate when all requests are cache hits")
    void testAllCacheHits() {
        stats.recordCacheHit();
        stats.recordCacheHit();
        assertEquals(1.0, stats.getCacheHitRate(), 0.001);
    }

    @Test
    @DisplayName("0% hit rate when all requests are cache misses")
    void testAllCacheMisses() {
        stats.recordCacheMiss();
        stats.recordCacheMiss();
        assertEquals(0.0, stats.getCacheHitRate(), 0.001);
    }

    @Test
    @DisplayName("50% cache hit rate with equal hits and misses")
    void testMixedCacheHitRate() {
        stats.recordCacheHit();
        stats.recordCacheMiss();
        assertEquals(0.5, stats.getCacheHitRate(), 0.001);
    }

    @Test
    @DisplayName("Average query time is 0.0 with no recorded queries")
    void testInitialAverageQueryTime() {
        assertEquals(0.0, stats.getAverageQueryTimeMs());
    }

    @Test
    @DisplayName("Average query time is correctly calculated in milliseconds")
    void testAverageQueryTime() {
        stats.recordQuery(1_000_000L); // 1ms in nanos
        stats.recordQuery(3_000_000L); // 3ms in nanos
        // Average = 2ms
        assertEquals(2.0, stats.getAverageQueryTimeMs(), 0.001);
    }

    @Test
    @DisplayName("getTotalQueries() returns correct count")
    void testTotalQueries() {
        assertEquals(0, stats.getTotalQueries());
        stats.recordQuery(500_000L);
        stats.recordQuery(750_000L);
        assertEquals(2, stats.getTotalQueries());
    }

    @Test
    @DisplayName("getCacheHits() and getCacheMisses() return raw counts")
    void testRawCounts() {
        stats.recordCacheHit();
        stats.recordCacheHit();
        stats.recordCacheMiss();
        assertEquals(2, stats.getCacheHits());
        assertEquals(1, stats.getCacheMisses());
    }
}
