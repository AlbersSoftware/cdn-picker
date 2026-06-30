package com.cdnworkbench.metrics;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Thread-safe, low-contention metrics accumulator.
 *
 * Hot-path counters (hits, misses, bytes) use LongAdder so concurrent
 * virtual threads never contend on a single CAS.  The latency sliding
 * window uses a bounded BlockingQueue (lock-free offer/poll).
 *
 * takeSnapshot() is called from the EDT (Swing Timer) every second and
 * computes interval-based bandwidth in Mbps.
 */
public final class MetricsCollector {

    private static final int LATENCY_WINDOW = 1_000;
    private static final int MAX_HISTORY    =   300;  // 5 min at 1-s interval

    // ---- Lock-free counters -----------------------------------------------
    private final LongAdder hits              = new LongAdder();
    private final LongAdder misses            = new LongAdder();
    private final LongAdder bytesDelivered    = new LongAdder();
    private final LongAdder bytesOrigin       = new LongAdder();
    private final LongAdder rebufferEvents    = new LongAdder();

    // Sliding latency samples (offered by virtual threads, drained by EDT)
    private final ArrayBlockingQueue<Long> latencyWindow =
            new ArrayBlockingQueue<>(LATENCY_WINDOW);

    // Snapshot history for chart replay
    private final List<MetricsSnapshot> history =
            Collections.synchronizedList(new ArrayList<>());

    // Active users map: userId → nodeId  (rough count, occasional miss is OK)
    private final ConcurrentHashMap<Integer, Integer> activeUsers = new ConcurrentHashMap<>();

    // Bandwidth interval state (accessed only from EDT snapshot thread)
    private long lastSnapMs             = System.currentTimeMillis();
    private long lastBytesDelivered     = 0;
    private long lastBytesOrigin        = 0;

    // ---- Event recording (called from virtual threads) -------------------

    public void recordCacheHit()  { hits.increment(); }
    public void recordCacheMiss() { misses.increment(); }

    public void recordRequest(long latencyMs) {
        if (latencyWindow.remainingCapacity() == 0) latencyWindow.poll();
        latencyWindow.offer(latencyMs);
    }

    public void recordBandwidth(long bytes) { bytesDelivered.add(bytes); }

    public void recordOriginHit(long bytes, long latencyMs) {
        bytesOrigin.add(bytes);
        recordRequest(latencyMs);    // origin latency counted in avg latency too
    }

    public void recordUserActive(int userId, int nodeId) {
        activeUsers.put(userId, nodeId);
    }

    /** Called when a viewer's simulated playback buffer empties (ABR stall). */
    public void recordRebuffer() { rebufferEvents.increment(); }

    // ---- Snapshot (called from EDT) --------------------------------------

    public MetricsSnapshot takeSnapshot() {
        long now     = System.currentTimeMillis();
        long elapsed = Math.max(1, now - lastSnapMs);

        long h = hits.sum(), m = misses.sum();
        double hitRate = (h + m) > 0 ? (double) h / (h + m) : 0.0;

        // Average latency from sliding window
        List<Long> samples = new ArrayList<>(latencyWindow);
        double avgLat = samples.isEmpty() ? 0.0 :
                samples.stream().mapToLong(Long::longValue).average().orElse(0.0);

        // Interval throughput in Mbps
        long curBytes   = bytesDelivered.sum();
        long curOrigin  = bytesOrigin.sum();
        double bwMbps     = (curBytes  - lastBytesDelivered) * 8.0 / (elapsed * 1_000.0);
        double origMbps   = (curOrigin - lastBytesOrigin)    * 8.0 / (elapsed * 1_000.0);

        lastSnapMs          = now;
        lastBytesDelivered  = curBytes;
        lastBytesOrigin     = curOrigin;

        MetricsSnapshot snap = new MetricsSnapshot(
                now, hitRate, avgLat, bwMbps, origMbps, h, m, h + m, rebufferEvents.sum());

        synchronized (history) {
            history.add(snap);
            if (history.size() > MAX_HISTORY) history.remove(0);
        }
        return snap;
    }

    public List<MetricsSnapshot> getHistory() {
        synchronized (history) { return new ArrayList<>(history); }
    }

    public int getActiveUserCount() { return activeUsers.size(); }

    public void reset() {
        hits.reset(); misses.reset();
        bytesDelivered.reset(); bytesOrigin.reset();
        rebufferEvents.reset();
        latencyWindow.clear();
        synchronized (history) { history.clear(); }
        activeUsers.clear();
        lastSnapMs          = System.currentTimeMillis();
        lastBytesDelivered  = 0;
        lastBytesOrigin     = 0;
    }
}
