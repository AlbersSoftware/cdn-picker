package com.cdnworkbench.cache;

import java.util.*;

/**
 * Clock (Second-Chance) cache replacement algorithm.
 *
 * Maintains a fixed-size circular buffer of slots.  Each slot carries a
 * "reference bit" that is set on every access.  The clock hand sweeps
 * the ring: a slot with bit=1 gets a second chance (bit cleared, hand
 * advances); the first slot with bit=0 is the eviction victim.
 *
 * Cost: O(1) amortised per get/put; at most one full sweep for a put.
 */
public final class ClockCache implements CachePolicy {

    private final int        capacity;
    private final String[]   keys;
    private final byte[][]   data;
    private final boolean[]  refBits;
    private int  hand  = 0;    // clock-hand position
    private int  count = 0;    // number of filled slots
    private long evictionCount = 0;

    /** key → slot index (for O(1) lookup) */
    private final Map<String, Integer> index = new HashMap<>();

    public ClockCache(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.keys    = new String [this.capacity];
        this.data    = new byte   [this.capacity][];
        this.refBits = new boolean[this.capacity];
    }

    @Override
    public synchronized byte[] get(String key) {
        Integer slot = index.get(key);
        if (slot == null) return null;
        refBits[slot] = true;    // mark as recently used
        return data[slot];
    }

    @Override
    public synchronized void put(String key, byte[] bytes) {
        // Update in-place if already resident
        Integer existing = index.get(key);
        if (existing != null) { data[existing] = bytes; refBits[existing] = true; return; }

        // Fill an empty slot during initial population
        if (count < capacity) {
            for (int i = 0; i < capacity; i++) {
                if (keys[i] == null) {
                    install(i, key, bytes);
                    count++;
                    return;
                }
            }
        }

        // Cache is full — sweep the clock hand to find a victim
        while (true) {
            if (refBits[hand]) {
                refBits[hand] = false;          // second chance: clear bit
            } else {
                // Evict this slot
                if (keys[hand] != null) { index.remove(keys[hand]); evictionCount++; }
                install(hand, key, bytes);
                hand = (hand + 1) % capacity;
                return;
            }
            hand = (hand + 1) % capacity;
        }
    }

    private void install(int slot, String key, byte[] bytes) {
        keys[slot]    = key;
        data[slot]    = bytes;
        refBits[slot] = true;
        index.put(key, slot);
    }

    @Override public synchronized boolean contains(String key) { return index.containsKey(key); }
    @Override public synchronized int size()     { return count; }
    @Override public             int capacity()   { return capacity; }

    @Override
    public synchronized List<String> getOrderedKeys() {
        List<String> result = new ArrayList<>(count);
        for (String k : keys) if (k != null) result.add(k);
        return result;
    }

    @Override
    public synchronized Map<String, Object> getEntryInfo(String key) {
        Integer slot = index.get(key);
        if (slot == null) return Collections.emptyMap();
        Map<String, Object> m = new HashMap<>();
        m.put("slot",         slot);
        m.put("referenced",   refBits[slot]);
        m.put("isHandSlot",   slot == hand);
        return m;
    }

    // Expose Clock-specific state for the visualizer
    public synchronized int       getHandPosition()  { return hand; }
    public synchronized boolean[] getRefBitsCopy()   { return refBits.clone(); }
    public synchronized String[]  getKeysCopy()      { return keys.clone(); }

    @Override public String getName() { return "CLOCK"; }

    @Override
    public synchronized void clear() {
        Arrays.fill(keys, null); Arrays.fill(data, null); Arrays.fill(refBits, false);
        index.clear(); hand = 0; count = 0; evictionCount = 0;
    }

    @Override public synchronized long getEvictionCount() { return evictionCount; }
}
