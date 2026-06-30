package com.cdnworkbench.cdn;

import com.cdnworkbench.cache.CachePolicy;
import com.cdnworkbench.metrics.MetricsCollector;

import java.util.Random;

/**
 * Simulates one CDN Point-of-Presence (PoP / edge node).
 *
 * requestSegment() is called concurrently by many virtual threads (one per
 * simulated user session).  Cache implementations are synchronized
 * internally; the only shared mutable state here is the volatile load
 * counter.
 */
public final class EdgeNode {

    /** Result of a segment request — payload plus whether it was a cache hit. */
    public record FetchResult(byte[] data, boolean hit) {}

    private final int            nodeId;
    private final String         region;
    private final CachePolicy    cache;
    private final OriginServer   origin;
    private final MetricsCollector metrics;
    private final Random         rng = new Random();

    private volatile int    activeUsers = 0;
    private volatile double load        = 0.0;

    public final double graphX, graphY;

    public EdgeNode(int nodeId, String region, CachePolicy cache,
                    OriginServer origin, MetricsCollector metrics,
                    double graphX, double graphY) {
        this.nodeId  = nodeId;
        this.region  = region;
        this.cache   = cache;
        this.origin  = origin;
        this.metrics = metrics;
        this.graphX  = graphX;
        this.graphY  = graphY;
    }

    /**
     * Serve a segment: cache hit → low-latency edge response;
     * cache miss → origin fetch + cache fill.
     * Safe to call from any virtual thread.
     */
    public FetchResult requestSegment(VideoSegment segment, SimulationConfig cfg) {
        String key = segment.key();

        byte[] data = cache.get(key);
        if (data != null) {
            metrics.recordCacheHit();
            simulateEdgeLatency(cfg.simulationSpeed());
            return new FetchResult(data, true);
        }

        metrics.recordCacheMiss();
        data = origin.fetchSegment(segment, cfg.simulationSpeed());
        if (data != null) cache.put(key, data);
        return new FetchResult(data, false);
    }

    private void simulateEdgeLatency(double speed) {
        try {
            long ms = (long) ((3 + rng.nextInt(8)) / Math.max(0.1, speed));
            Thread.sleep(Math.max(ms, 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void  setActiveUsers(int n)  { activeUsers = n; load = Math.min(1.0, n / 25.0); }

    public int         getNodeId()      { return nodeId; }
    public String      getRegion()      { return region; }
    public CachePolicy getCache()       { return cache; }
    public int         getActiveUsers() { return activeUsers; }
    public double      getLoad()        { return load; }
}
