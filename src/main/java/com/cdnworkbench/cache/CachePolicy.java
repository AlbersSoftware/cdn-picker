package com.cdnworkbench.cache;

import java.util.List;
import java.util.Map;

/**
 * Common contract for all cache-replacement algorithms.
 * All implementations must be thread-safe — they are accessed concurrently
 * by many virtual threads through the owning EdgeNode.
 */
public interface CachePolicy {

    /** Return cached bytes, or null on miss.  Must update internal access metadata. */
    byte[] get(String key);

    /** Store a segment.  Evicts as needed according to the algorithm. */
    void put(String key, byte[] data);

    /** Existence check that does NOT update access metadata (for prefetch guard). */
    boolean contains(String key);

    /** Current number of resident entries. */
    int size();

    /** Maximum resident entries. */
    int capacity();

    /**
     * Ordered key list for the cache visualizer.
     * Convention: index 0 = most-recently-used / highest-priority end.
     */
    List<String> getOrderedKeys();

    /**
     * Per-entry metadata for the visualizer (frequency, list name, reference bit …).
     * Returns an empty map if the algorithm has no relevant per-entry metadata.
     */
    Map<String, Object> getEntryInfo(String key);

    /** Human-readable algorithm name. */
    String getName();

    /** Discard all entries and reset counters. */
    void clear();

    /** Total evictions since creation / last clear. */
    long getEvictionCount();
}
