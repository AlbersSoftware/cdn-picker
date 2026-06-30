package com.cdnworkbench.cdn;

/**
 * Immutable descriptor for one HLS/DASH-style video segment.
 * The cache key encodes all three dimensions so different bitrate
 * renditions of the same segment are stored as separate entries.
 */
public record VideoSegment(
        String videoId,
        int    segmentIndex,
        int    bitrateKbps,
        int    durationSeconds
) {
    /** Unique cache key: "video001:5@1500" */
    public String key() {
        return videoId + ":" + segmentIndex + "@" + bitrateKbps;
    }

    /** Approximate payload size in bytes for this segment. */
    public int sizeBytes() {
        return (bitrateKbps * 1_000 / 8) * durationSeconds;
    }
}
