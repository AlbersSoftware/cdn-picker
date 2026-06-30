package com.cdnworkbench.cdn;

import com.cdnworkbench.metrics.MetricsCollector;

import java.io.IOException;
import java.util.Random;

/**
 * Simulates an origin content server.
 *
 * Two modes:
 *   Synthetic (default) — random bytes, Zipf video catalogue.
 *   Real MP4 (when VideoFile is set) — actual byte ranges from the loaded
 *   file, capped per-segment at VideoFile.MAX_SERVE_BYTES for memory safety.
 *
 * Bandwidth / origin-traffic metrics always use segment.sizeBytes() (derived
 * from the selected bitrate ladder), NOT the raw cached payload length —
 * this keeps the charts accurate to the simulated bitrate regardless of the
 * internal memory cap on cached bytes.
 *
 * fetchSegment() must be called from a virtual thread: Thread.sleep() parks
 * the VT without blocking any OS carrier thread.
 */
public final class OriginServer {

    public  static final int NUM_VIDEOS         = 20;
    public  static final int SEGMENTS_PER_VIDEO = 150;

    private static final int BASE_LATENCY_MS = 80;
    private static final int JITTER_MS       = 40;
    private static final int MAX_SYNTH_BYTES = 1_024;

    private final MetricsCollector metrics;
    private final Random           rng = new Random();

    /** Non-null → serve real MP4 bytes instead of synthetic data. */
    private volatile VideoFile videoFile = null;

    public OriginServer(MetricsCollector metrics) { this.metrics = metrics; }

    public void setVideoFile(VideoFile vf) { this.videoFile = vf; }
    public VideoFile getVideoFile()        { return videoFile; }

    // ---- Fetch (called from virtual threads) ------------------------------

    public byte[] fetchSegment(VideoSegment segment, double speedMultiplier) {
        long latMs = (long)((BASE_LATENCY_MS + rng.nextInt(JITTER_MS))
                            / Math.max(0.1, speedMultiplier));
        try {
            Thread.sleep(Math.max(latMs, 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        VideoFile vf = this.videoFile;       // snapshot for thread-safety
        if (vf != null) {
            try {
                byte[] real = vf.getSegmentBytes(segment.segmentIndex());
                // Metrics use the bitrate-derived nominal size, not the
                // capped cache payload length, so bandwidth charts reflect
                // the actual selected quality tier.
                metrics.recordOriginHit(segment.sizeBytes(), latMs);
                return real;
            } catch (IOException ignored) { /* fall through to synthetic */ }
        }

        // Synthetic: pseudo-random payload
        byte[] payload = new byte[Math.min(segment.sizeBytes(), MAX_SYNTH_BYTES)];
        rng.nextBytes(payload);
        metrics.recordOriginHit(segment.sizeBytes(), latMs);
        return payload;
    }

    // ---- Zipf video selection (synthetic mode) ----------------------------

    public static String selectVideoId(Random rng) {
        double[] w = new double[NUM_VIDEOS];
        double total = 0;
        for (int i = 0; i < NUM_VIDEOS; i++) { w[i] = 1.0 / (i + 1); total += w[i]; }
        double r = rng.nextDouble() * total;
        for (int i = 0; i < NUM_VIDEOS; i++) {
            r -= w[i]; if (r <= 0) return String.format("video%03d", i + 1);
        }
        return "video001";
    }
}
