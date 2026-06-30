package com.cdnworkbench.cache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Predictive prefetch wrapper around any {@link CachePolicy}.
 *
 * After each cache hit the wrapper inspects a per-thread Markov-chain
 * transition table to predict the next N segments likely to be requested.
 * Each predicted segment that is not already resident is pre-fetched on a
 * dedicated Java 21 virtual thread — so the prefetch never blocks the
 * calling thread.
 *
 * The transition table is updated on every get() call and converges toward
 * real access patterns over time.
 */
public final class PredictiveCache implements CachePolicy {

    /** Caller-supplied function that fetches bytes from origin for a given key. */
    @FunctionalInterface
    public interface Fetcher { byte[] fetch(String key); }

    private static final int PREFETCH_AHEAD = 2;   // segments to prefetch per hit

    private final CachePolicy delegate;
    private final Fetcher     fetcher;

    // Markov chain: transitions.get(A).get(B) = how often A was followed by B
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> transitions =
        new ConcurrentHashMap<>();

    // Keys currently being prefetched (avoids duplicate in-flight requests)
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());

    // Per-virtual-thread last-seen key for transition recording
    private final ThreadLocal<String> prevKey = ThreadLocal.withInitial(() -> null);

    public PredictiveCache(CachePolicy delegate, Fetcher fetcher) {
        this.delegate = delegate;
        this.fetcher  = fetcher;
    }

    @Override
    public byte[] get(String key) {
        // Record transition A → key
        String prev = prevKey.get();
        if (prev != null) {
            transitions.computeIfAbsent(prev, k -> new ConcurrentHashMap<>())
                       .merge(key, 1, Integer::sum);
        }
        prevKey.set(key);

        byte[] result = delegate.get(key);
        if (result != null) prefetchFrom(key);   // hit: trigger prefetch
        return result;
    }

    private void prefetchFrom(String key) {
        for (String predicted : predict(key)) {
            if (!delegate.contains(predicted) && inFlight.add(predicted)) {
                Thread.ofVirtual()
                      .name("cdn-prefetch-" + predicted)
                      .start(() -> {
                          try {
                              byte[] data = fetcher.fetch(predicted);
                              if (data != null) delegate.put(predicted, data);
                          } finally {
                              inFlight.remove(predicted);
                          }
                      });
            }
        }
    }

    private List<String> predict(String key) {
        ConcurrentHashMap<String, Integer> nexts = transitions.get(key);
        if (nexts != null && !nexts.isEmpty()) {
            return nexts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(PREFETCH_AHEAD)
                .map(Map.Entry::getKey)
                .toList();
        }
        // No history yet: fall back to sequential segment prediction
        return predictSequential(key);
    }

    /** Predict next segments by incrementing the segment index in the key. */
    private List<String> predictSequential(String key) {
        List<String> result = new ArrayList<>();
        try {
            // Key format: videoId:segIdx@bitrateKbps
            String[] colon = key.split(":", 2);
            String[] at    = colon[1].split("@", 2);
            int seg        = Integer.parseInt(at[0]);
            for (int i = 1; i <= PREFETCH_AHEAD; i++)
                result.add(colon[0] + ":" + (seg + i) + "@" + at[1]);
        } catch (Exception ignored) {}
        return result;
    }

    // ---- Delegate all other CachePolicy methods ----

    @Override public void      put(String key, byte[] data) { delegate.put(key, data); }
    @Override public boolean   contains(String key)         { return delegate.contains(key); }
    @Override public int       size()                       { return delegate.size(); }
    @Override public int       capacity()                   { return delegate.capacity(); }
    @Override public List<String>        getOrderedKeys()    { return delegate.getOrderedKeys(); }
    @Override public Map<String, Object> getEntryInfo(String key) { return delegate.getEntryInfo(key); }
    @Override public String    getName()                    { return "Pred+" + delegate.getName(); }
    @Override public void      clear()                      { delegate.clear(); transitions.clear(); inFlight.clear(); }
    @Override public long      getEvictionCount()           { return delegate.getEvictionCount(); }

    /** Expose the underlying base cache so the visualizer can inspect algorithm-specific state. */
    public CachePolicy getBaseCache() { return delegate; }

    public int getPrefetchQueueSize() { return inFlight.size(); }
}
