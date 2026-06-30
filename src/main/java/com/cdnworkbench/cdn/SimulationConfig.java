package com.cdnworkbench.cdn;

/** All knobs the user can tweak before starting a simulation run. */
public record SimulationConfig(
        String  algorithm,            // "LRU" | "LFU" | "ARC" | "CLOCK"
        int     segmentLengthSeconds,
        int[]   bitrateKbps,          // e.g. [400, 800, 1500, 4000]
        int     bufferTargetSeconds,
        boolean predictiveCaching,
        int     edgeNodeCount,
        int     userCount,
        int     cacheSizeSegments,    // per-node cache capacity in segments
        double  simulationSpeed,      // 1 = real-time, 10 = 10x faster
        boolean departureMode,        // true → viewers leave for good after one pass
        int     dropOffPercent        // 0-100: chance a real-MP4 viewing pass ends early
) {
    public static SimulationConfig defaults() {
        return new SimulationConfig(
                "LRU",
                4,
                new int[]{400, 800, 1500, 4000},
                15,
                false,
                3,
                20,
                100,
                5.0,
                false,
                25
        );
    }
}
