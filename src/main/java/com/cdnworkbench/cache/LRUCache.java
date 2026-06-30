package com.cdnworkbench.cache;

import java.util.*;

/**
 * Least-Recently-Used cache backed by an access-ordered LinkedHashMap.
 * Eviction of the least-recently-used entry is O(1).
 */
public final class LRUCache implements CachePolicy {

    private final int capacity;
    private long evictionCount = 0;

    private final LinkedHashMap<String, byte[]> map;

    public LRUCache(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.map = new LinkedHashMap<>(this.capacity, 0.75f, /*accessOrder=*/true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                if (size() > LRUCache.this.capacity) {
                    evictionCount++;
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public synchronized byte[] get(String key) {
        return map.get(key);          // access-order update is automatic
    }

    @Override
    public synchronized void put(String key, byte[] data) {
        map.put(key, data);
    }

    @Override
    public synchronized boolean contains(String key) {
        return map.containsKey(key);  // no order update
    }

    @Override
    public synchronized int size() { return map.size(); }

    @Override
    public int capacity() { return capacity; }

    @Override
    public synchronized List<String> getOrderedKeys() {
        // LinkedHashMap iterates LRU→MRU; reverse for MRU-first convention
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.reverse(keys);
        return keys;
    }

    @Override
    public Map<String, Object> getEntryInfo(String key) {
        return Collections.emptyMap();
    }

    @Override
    public String getName() { return "LRU"; }

    @Override
    public synchronized void clear() { map.clear(); evictionCount = 0; }

    @Override
    public synchronized long getEvictionCount() { return evictionCount; }
}
