package com.cdnworkbench.cache;

import java.util.*;

/**
 * Least-Frequently-Used cache.
 * Uses frequency-bucket lists to achieve O(1) amortised get/put/evict.
 * On frequency tie the least-recently-used entry in that bucket is evicted.
 */
public final class LFUCache implements CachePolicy {

    private final int capacity;
    private final Map<String, byte[]>           dataMap    = new HashMap<>();
    private final Map<String, Integer>          freqMap    = new HashMap<>();
    /** Sorted ascending by frequency; each bucket is insertion-ordered (LRU-within-freq). */
    private final TreeMap<Integer, LinkedHashSet<String>> buckets = new TreeMap<>();
    private int  minFreq = 0;
    private long evictionCount = 0;

    public LFUCache(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public synchronized byte[] get(String key) {
        if (!dataMap.containsKey(key)) return null;
        promote(key);
        return dataMap.get(key);
    }

    @Override
    public synchronized void put(String key, byte[] data) {
        if (capacity <= 0) return;
        if (dataMap.containsKey(key)) {
            dataMap.put(key, data);
            promote(key);
            return;
        }
        if (dataMap.size() >= capacity) evict();
        dataMap.put(key, data);
        freqMap.put(key, 1);
        buckets.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
        minFreq = 1;
    }

    private void promote(String key) {
        int f = freqMap.get(key);
        freqMap.put(key, f + 1);
        LinkedHashSet<String> bucket = buckets.get(f);
        bucket.remove(key);
        if (bucket.isEmpty()) {
            buckets.remove(f);
            if (minFreq == f) minFreq = f + 1;
        }
        buckets.computeIfAbsent(f + 1, k -> new LinkedHashSet<>()).add(key);
    }

    private void evict() {
        LinkedHashSet<String> minBucket = buckets.get(minFreq);
        String victim = minBucket.iterator().next();
        minBucket.remove(victim);
        if (minBucket.isEmpty()) buckets.remove(minFreq);
        dataMap.remove(victim);
        freqMap.remove(victim);
        evictionCount++;
    }

    @Override
    public synchronized boolean contains(String key) { return dataMap.containsKey(key); }

    @Override
    public synchronized int size() { return dataMap.size(); }

    @Override
    public int capacity() { return capacity; }

    @Override
    public synchronized List<String> getOrderedKeys() {
        // Highest frequency first
        List<String> keys = new ArrayList<>(dataMap.keySet());
        keys.sort((a, b) -> freqMap.getOrDefault(b, 0) - freqMap.getOrDefault(a, 0));
        return keys;
    }

    @Override
    public synchronized Map<String, Object> getEntryInfo(String key) {
        Map<String, Object> info = new HashMap<>();
        info.put("frequency", freqMap.getOrDefault(key, 0));
        return info;
    }

    @Override
    public String getName() { return "LFU"; }

    @Override
    public synchronized void clear() {
        dataMap.clear(); freqMap.clear(); buckets.clear(); minFreq = 0; evictionCount = 0;
    }

    @Override
    public synchronized long getEvictionCount() { return evictionCount; }
}
