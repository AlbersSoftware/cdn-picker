package com.cdnworkbench.metrics;

/** Immutable point-in-time snapshot passed from the simulation to the UI. */
public record MetricsSnapshot(
        long   timestamp,
        double hitRate,             // 0.0 – 1.0
        double avgLatencyMs,
        double bandwidthMbps,       // total delivered
        double originTrafficMbps,   // origin-only traffic
        long   totalHits,
        long   totalMisses,
        long   totalRequests,
        long   totalRebuffers       // cumulative ABR stall/rebuffer events
) {}
