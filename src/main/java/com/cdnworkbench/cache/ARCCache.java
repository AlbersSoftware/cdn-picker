package com.cdnworkbench.cache;

import java.util.*;

/**
 * Adaptive Replacement Cache (ARC) — Megiddo & Modha, 2003.
 *
 * Maintains four lists:
 *   T1 — recently accessed once        (live data, in cache)
 *   T2 — accessed at least twice       (live data, in cache)
 *   B1 — ghost of recently evicted T1  (keys only, no data)
 *   B2 — ghost of recently evicted T2  (keys only, no data)
 *
 * The adaptive parameter p (target size for T1) grows when B1 is hit
 * and shrinks when B2 is hit, balancing recency vs. frequency.
 */
public final class ARCCache implements CachePolicy {

    private final int capacity;
    private int p = 0;    // target T1 size; adapts at runtime

    // Live data lists (insertion-order = LRU-to-MRU)
    private final LinkedHashMap<String, byte[]> t1 = new LinkedHashMap<>();
    private final LinkedHashMap<String, byte[]> t2 = new LinkedHashMap<>();
    // Ghost lists (keys only, no data)
    private final LinkedHashSet<String> b1 = new LinkedHashSet<>();
    private final LinkedHashSet<String> b2 = new LinkedHashSet<>();

    private long evictionCount = 0;

    public ARCCache(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public synchronized byte[] get(String key) {
        // T1 hit → promote to MRU of T2
        byte[] data = t1.remove(key);
        if (data != null) { t2.put(key, data); return data; }
        // T2 hit → move to MRU of T2
        data = t2.remove(key);
        if (data != null) { t2.put(key, data); return data; }
        return null;
    }

    @Override
    public synchronized void put(String key, byte[] data) {
        // Already in live cache: update and move to MRU of T2
        if (t1.containsKey(key)) { t1.remove(key); t2.put(key, data); return; }
        if (t2.containsKey(key)) { t2.remove(key); t2.put(key, data); return; }

        if (b1.contains(key)) {
            // B1 hit: recent page missed more — grow T1 target
            p = Math.min(capacity, p + Math.max(1, b2.size() / Math.max(1, b1.size())));
            replace(key); b1.remove(key); t2.put(key, data); return;
        }
        if (b2.contains(key)) {
            // B2 hit: frequent page missed more — shrink T1 target
            p = Math.max(0, p - Math.max(1, b1.size() / Math.max(1, b2.size())));
            replace(key); b2.remove(key); t2.put(key, data); return;
        }

        // Completely new entry
        int liveSize  = t1.size() + t2.size();
        int totalSize = liveSize + b1.size() + b2.size();

        if (t1.size() + b1.size() >= capacity) {
            if (t1.size() < capacity) { trimB1(); replace(null); }
            else                       { evictFromT1(); }
        } else if (totalSize >= 2 * capacity) {
            trimB2();
            if (liveSize >= capacity) replace(null);
        } else if (liveSize >= capacity) {
            replace(null);
        }
        t1.put(key, data);
    }

    /**
     * Evict one entry from either T1 or T2 (based on p) and ghost it.
     * @param incomingKey the key being inserted (used to resolve the B2-tie-break)
     */
    private void replace(String incomingKey) {
        int t1Size = t1.size();
        boolean preferT1 = !t1.isEmpty() &&
            (t1Size > p || (incomingKey != null && b2.contains(incomingKey) && t1Size == p));
        if (preferT1) {
            String victim = t1.keySet().iterator().next();   // LRU of T1
            t1.remove(victim); b1.add(victim); evictionCount++;
        } else if (!t2.isEmpty()) {
            String victim = t2.keySet().iterator().next();   // LRU of T2
            t2.remove(victim); b2.add(victim); evictionCount++;
        }
    }

    private void evictFromT1() { if (!t1.isEmpty()) { t1.remove(t1.keySet().iterator().next()); evictionCount++; } }
    private void trimB1()      { if (!b1.isEmpty()) b1.remove(b1.iterator().next()); }
    private void trimB2()      { if (!b2.isEmpty()) b2.remove(b2.iterator().next()); }

    @Override
    public synchronized boolean contains(String key) {
        return t1.containsKey(key) || t2.containsKey(key);
    }

    @Override public synchronized int size()     { return t1.size() + t2.size(); }
    @Override public             int capacity()   { return capacity; }

    @Override
    public synchronized List<String> getOrderedKeys() {
        List<String> keys = new ArrayList<>();
        keys.addAll(t2.keySet());   // T2 (frequent) first
        keys.addAll(t1.keySet());   // T1 (recent) after
        return keys;
    }

    @Override
    public synchronized Map<String, Object> getEntryInfo(String key) {
        Map<String, Object> info = new HashMap<>();
        if      (t1.containsKey(key)) info.put("list", "T1");
        else if (t2.containsKey(key)) info.put("list", "T2");
        return info;
    }

    // Expose ARC-specific state for the visualizer
    public synchronized int getP()     { return p; }
    public synchronized int getT1Size(){ return t1.size(); }
    public synchronized int getT2Size(){ return t2.size(); }
    public synchronized int getB1Size(){ return b1.size(); }
    public synchronized int getB2Size(){ return b2.size(); }

    @Override public String getName() { return "ARC"; }

    @Override
    public synchronized void clear() {
        t1.clear(); t2.clear(); b1.clear(); b2.clear(); p = 0; evictionCount = 0;
    }

    @Override public synchronized long getEvictionCount() { return evictionCount; }
}
