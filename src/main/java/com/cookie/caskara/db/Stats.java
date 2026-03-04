package com.cookie.caskara.db;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks internal performance metrics for Caskara.
 */
public class Stats {
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong queryTotalTimeNs = new AtomicLong(0);
    private final AtomicLong queryCount = new AtomicLong(0);

    public void recordCacheHit() { 
        cacheHits.incrementAndGet(); 
    }
    
    public void recordCacheMiss() { 
        cacheMisses.incrementAndGet(); 
    }
    
    public void recordQuery(long timeNs) {
        queryTotalTimeNs.addAndGet(timeNs);
        queryCount.incrementAndGet();
    }

    /**
     * Percentage of requests served directly from the LRU cache.
     */
    public double getCacheHitRate() {
        long total = cacheHits.get() + cacheMisses.get();
        if (total == 0) return 0.0;
        return (double) cacheHits.get() / total;
    }

    /**
     * Average time for SQL query execution in milliseconds.
     */
    public double getAverageQueryTimeMs() {
        long count = queryCount.get();
        if (count == 0) return 0.0;
        return (double) queryTotalTimeNs.get() / count / 1_000_000.0;
    }

    public long getTotalQueries() {
        return queryCount.get();
    }

    public long getCacheHits() { return cacheHits.get(); }
    public long getCacheMisses() { return cacheMisses.get(); }
}
