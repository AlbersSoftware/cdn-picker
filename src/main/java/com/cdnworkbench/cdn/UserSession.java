package com.cdnworkbench.cdn;

import com.cdnworkbench.metrics.MetricsCollector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates one concurrent viewer.  Each instance runs on its own
 * Java 21 virtual thread — Thread.sleep() inside is essentially free.
 *
 * Two operating modes:
 *   Synthetic — picks videos from the Zipf catalogue of 20 synthetic titles,
 *               watching a random sub-window of arbitrary length/position.
 *   Real MP4  — every user streams the loaded VideoFile, starting near the
 *               beginning and mostly playing through to the actual end
 *               (cfg.dropOffPercent() chance of an early drop-off, modeling
 *               real viewer abandonment).
 *
 * ABR (adaptive bitrate): bitrate is now chosen PER SEGMENT, not once per
 * viewing pass, using a simulated last-mile connection:
 *   - Each session has its own simulated bandwidth (bandwidthKbps) that
 *     random-walks over time, representing the viewer's actual home/mobile
 *     network condition — this is separate from, and in addition to, the
 *     existing CDN-side edge/origin latency model in EdgeNode/OriginServer.
 *   - Segment transfer time is derived from segment size / current
 *     bandwidth, so larger (higher-bitrate) segments genuinely take longer
 *     to pull down — fetch time is no longer independent of bitrate choice.
 *   - An EWMA of measured per-segment throughput feeds a throughput-safe
 *     bitrate ceiling (never committing 100% of the estimate, same as real
 *     players); a virtual playback buffer tracks whether downloads are
 *     keeping ahead of playback, forcing a drop to the lowest rung when
 *     critically low and counting a rebuffer/stall event if it empties.
 *   - Upward bitrate steps are capped at one rung per segment (hysteresis)
 *     to avoid oscillating on a noisy estimate; downward steps are
 *     unrestricted, since reacting fast to degraded conditions is safer.
 * This is a simplified hybrid throughput+buffer heuristic in the spirit of
 * real ABR algorithms, not a reproduction of any one specific published
 * algorithm (e.g. BOLA) — see the project's accuracy discussion for caveats.
 *
 * Sessions loop forever by default (new viewing pass after each one
 * finishes) to keep the simulated CDN under sustained load. With
 * SimulationConfig.departureMode() enabled, a session instead exits for
 * good after one pass, modeling viewers who leave once they're done.
 *
 * "Now playing" state is exposed via volatile fields + a lock-free history
 * deque so the Playback tab can read live state from the EDT without
 * blocking this thread (single-writer/single-reader, no lock needed).
 */
public final class UserSession implements Runnable {

    private static final int HISTORY_SIZE = 30;

    // ── ABR tuning constants ────────────────────────────────────────────
    private static final double EWMA_ALPHA        = 0.30;   // throughput estimator smoothing
    private static final double ABR_SAFETY_FACTOR = 0.85;   // never commit 100% of the estimate
    private static final double BANDWIDTH_MIN_KBPS = 250;
    private static final double BANDWIDTH_MAX_KBPS = 9_000;
    private static final double BANDWIDTH_DRIFT_PCT = 0.25; // max +/-25% random-walk step/segment

    private final int              userId;
    private final EdgeNode         edgeNode;
    private final MetricsCollector metrics;
    private final SimulationConfig cfg;
    private final VideoFile        videoFile;       // null → synthetic mode
    private final AtomicInteger    activeCounter;    // shared "live VT" tally
    private final Random           rng;
    private volatile boolean       running = true;

    // ── Simulated last-mile network state (drives ABR) ─────────────────────
    private double bandwidthKbps;              // current simulated connection capacity
    private double throughputEstimateKbps = 0; // EWMA of measured per-segment throughput
    private double bufferSeconds          = 0; // virtual playback buffer, reset each new video
    private int    currentBitrateKbps     = 0; // last bitrate chosen, for hysteresis

    // ── Now-playing state (read by PlaybackPanel on the EDT) ──────────────
    private volatile String  npVideoId          = "";
    private volatile int     npSegmentIndex      = -1;
    private volatile int     npTotalSegments     = 0;
    private volatile int     npBitrateKbps       = 0;
    private volatile double  npTimestampSeconds  = 0;
    private volatile double  npDurationSeconds   = 0;
    private volatile boolean npLastHit           = false;
    private volatile double  npBandwidthKbps     = 0;   // simulated connection (ground truth)
    private volatile double  npThroughputEstKbps = 0;   // what the ABR logic actually believes
    private volatile double  npBufferSeconds     = 0;

    /** Single-writer (this thread) / single-reader (EDT) — no lock needed. */
    private final ConcurrentLinkedDeque<Boolean> recentHits = new ConcurrentLinkedDeque<>();

    public UserSession(int userId, EdgeNode edgeNode,
                       MetricsCollector metrics, SimulationConfig cfg,
                       VideoFile videoFile, AtomicInteger activeCounter) {
        this.userId        = userId;
        this.edgeNode       = edgeNode;
        this.metrics        = metrics;
        this.cfg            = cfg;
        this.videoFile      = videoFile;
        this.activeCounter  = activeCounter;
        this.rng            = new Random(userId * 31_337L);
        // Initial "home network quality" — persists and random-walks across
        // this session's lifetime, including across multiple videos.
        this.bandwidthKbps  = 800 + rng.nextDouble() * 4200;
    }

    @Override
    public void run() {
        activeCounter.incrementAndGet();
        try {
            if (userId == 0) {
                Thread t = Thread.currentThread();
                System.out.printf("[VT] User-0 thread: name=\"%s\"  isVirtual=%b%n",
                                  t.getName(), t.isVirtual());
            }

            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    streamOneVideo();
                    if (cfg.departureMode()) {
                        // Viewer leaves for good after this pass — thread
                        // exits, decrementing the live VT count.
                        running = false;
                        break;
                    }
                    Thread.sleep(Math.max(1L, (long)(1_500 / cfg.simulationSpeed())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            activeCounter.decrementAndGet();
        }
    }

    private void streamOneVideo() throws InterruptedException {
        String videoId;
        int    totalSegments;
        double totalDuration;
        int    startSeg, endSeg;

        if (videoFile != null) {
            // ── Real MP4 mode ───────────────────────────────────────────
            videoId       = videoFile.getVideoId();
            totalSegments = videoFile.getSegmentCount();
            totalDuration = videoFile.getDurationSeconds();

            // Start near the beginning (staggered viewer join times), then
            // mostly play through to the real end so segment progress is
            // coherent. cfg.dropOffPercent() of sessions leave early.
            startSeg = rng.nextInt(Math.max(1, totalSegments / 10 + 1));
            boolean dropOffEarly = rng.nextDouble() < (cfg.dropOffPercent() / 100.0);
            endSeg = dropOffEarly
                ? Math.min(totalSegments - 1, startSeg + rng.nextInt(Math.max(1, totalSegments / 2)))
                : totalSegments - 1;
        } else {
            // ── Synthetic mode ──────────────────────────────────────────
            videoId       = OriginServer.selectVideoId(rng);
            totalSegments = OriginServer.SEGMENTS_PER_VIDEO;
            totalDuration = totalSegments * cfg.segmentLengthSeconds();

            startSeg = rng.nextInt(Math.max(1, totalSegments / 2));
            int watchLen = rng.nextInt(25) + 5;
            endSeg = Math.min(startSeg + watchLen, totalSegments - 1);
        }

        // New video, new playback buffer and bitrate history — but the
        // bandwidth estimate/connection state carries over (your home
        // network doesn't change just because you clicked a new title).
        bufferSeconds      = 0;
        currentBitrateKbps = 0;
        metrics.recordUserActive(userId, edgeNode.getNodeId());

        for (int seg = startSeg; seg <= endSeg && running; seg++) {
            driftBandwidth();
            int bitrateKbps = chooseAbrBitrate(cfg.bitrateKbps());
            currentBitrateKbps = bitrateKbps;

            VideoSegment segment = new VideoSegment(
                    videoId, seg, bitrateKbps, cfg.segmentLengthSeconds());

            // CDN-side fetch: existing edge/origin latency model (unchanged).
            long cdnFetchStartMs = System.currentTimeMillis();
            EdgeNode.FetchResult result = edgeNode.requestSegment(segment, cfg);
            long cdnFetchMs = System.currentTimeMillis() - cdnFetchStartMs;

            if (result.data() != null) {
                // Last-mile transfer: how long pulling THIS segment's bytes
                // actually takes over the viewer's current simulated
                // connection — this is what makes bitrate choice and fetch
                // time genuinely linked, and what gives the ABR algorithm
                // something real to measure.
                double transferSeconds = (segment.sizeBytes() * 8.0) / (bandwidthKbps * 1_000.0);
                long   transferSleepMs = (long) (transferSeconds * 1_000 / cfg.simulationSpeed());
                Thread.sleep(Math.max(transferSleepMs, 1));

                long totalLatencyMs = cdnFetchMs + transferSleepMs;
                metrics.recordRequest(totalLatencyMs);
                metrics.recordBandwidth(segment.sizeBytes());

                // Feed the EWMA throughput estimator from what was actually
                // observed this segment (bytes / time) — exactly what a
                // real player's throughput estimator does.
                double measuredKbps = (segment.sizeBytes() * 8.0 / 1_000.0) / Math.max(transferSeconds, 0.001);
                throughputEstimateKbps = (throughputEstimateKbps <= 0)
                    ? measuredKbps
                    : EWMA_ALPHA * measuredKbps + (1 - EWMA_ALPHA) * throughputEstimateKbps;

                // Buffer dynamics: scale the measured CDN latency back up to
                // its "real-world equivalent" seconds (it was compressed by
                // simulationSpeed when sleeping), same convention used
                // throughout EdgeNode/OriginServer's own latency model.
                double cdnRealSeconds = (cdnFetchMs / 1_000.0) * cfg.simulationSpeed();
                double downloadSeconds = cdnRealSeconds + transferSeconds;

                bufferSeconds += cfg.segmentLengthSeconds() - downloadSeconds;
                if (bufferSeconds < 0) {
                    metrics.recordRebuffer();
                    bufferSeconds = 0;
                }
                double bufferCap = Math.max(cfg.bufferTargetSeconds() * 2.0, cfg.segmentLengthSeconds() * 3.0);
                bufferSeconds = Math.min(bufferSeconds, bufferCap);
            }

            updateNowPlaying(videoId, seg, totalSegments, bitrateKbps, result.hit(), totalDuration);

            // Playback: watch this segment for its real duration. Kept
            // separate from the download time above — a simplification
            // shared with most turn-based ABR simulators (fetch-then-play
            // per segment) rather than a fully concurrent download/playback
            // pipeline.
            long playMs = (long) (cfg.segmentLengthSeconds() * 1_000L / cfg.simulationSpeed());
            Thread.sleep(Math.max(playMs, 5));
        }
    }

    /** Random-walks the simulated last-mile bandwidth, bounded to a realistic range. */
    private void driftBandwidth() {
        double factor = 1.0 + (rng.nextDouble() * 2 - 1) * BANDWIDTH_DRIFT_PCT;
        bandwidthKbps = clamp(bandwidthKbps * factor, BANDWIDTH_MIN_KBPS, BANDWIDTH_MAX_KBPS);
    }

    /**
     * Hybrid throughput + buffer bitrate selection, evaluated fresh for
     * every segment. See the class doc for the full rationale.
     */
    private int chooseAbrBitrate(int[] ladder) {
        if (ladder.length == 0) return 800;

        // Throughput-safe ceiling: highest rung the EWMA estimate can
        // sustain with headroom. Assumes ladder is ascending, which holds
        // here since ControlPanel always builds it in 400/800/1500/4000 order.
        double safeKbps = throughputEstimateKbps * ABR_SAFETY_FACTOR;
        int throughputChoice = ladder[0];
        for (int rate : ladder) if (rate <= safeKbps) throughputChoice = rate;

        // Buffer panic: a critically low buffer overrides everything —
        // recover first, climb later. Threshold scales with the user's
        // configured buffer target.
        double panicThreshold = getBufferPanicThresholdSeconds();
        if (bufferSeconds < panicThreshold) return ladder[0];

        // No bitrate history yet this video — take the throughput-safe
        // pick directly (which naturally starts conservative, since the
        // throughput estimate is itself still 0 on the very first segment).
        if (currentBitrateKbps == 0) return throughputChoice;

        // Hysteresis: cap upward jumps at one rung per segment so a noisy
        // estimate doesn't cause oscillation; downward jumps are immediate.
        int curIdx    = indexOf(ladder, currentBitrateKbps);
        int targetIdx = indexOf(ladder, throughputChoice);
        if (targetIdx > curIdx + 1) targetIdx = curIdx + 1;
        return ladder[Math.max(0, Math.min(ladder.length - 1, targetIdx))];
    }

    private static int indexOf(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return 0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void updateNowPlaying(String videoId, int seg, int totalSegments,
                                  int bitrateKbps, boolean hit, double totalDuration) {
        npVideoId          = videoId;
        npSegmentIndex      = seg;
        npTotalSegments     = totalSegments;
        npBitrateKbps       = bitrateKbps;
        npLastHit           = hit;
        npDurationSeconds   = totalDuration;
        npTimestampSeconds  = seg * (double) cfg.segmentLengthSeconds();
        npBandwidthKbps     = bandwidthKbps;
        npThroughputEstKbps = throughputEstimateKbps;
        npBufferSeconds     = bufferSeconds;

        recentHits.addLast(hit);
        while (recentHits.size() > HISTORY_SIZE) recentHits.pollFirst();
    }

    public void stop() { running = false; }

    /** False once this session has departed (departure mode) or been stopped. */
    public boolean isActive() { return running; }

    // ── Public getters for the Playback tab (EDT-safe reads) ───────────────

    public int     getUserId()                { return userId; }
    public EdgeNode getEdgeNode()              { return edgeNode; }
    public String  getNowPlayingVideoId()      { return npVideoId; }
    public int     getNowPlayingSegmentIndex() { return npSegmentIndex; }
    public int     getNowPlayingTotalSegments(){ return npTotalSegments; }
    public int     getNowPlayingBitrateKbps()  { return npBitrateKbps; }
    public double  getNowPlayingTimestampSeconds() { return npTimestampSeconds; }
    public double  getNowPlayingDurationSeconds()  { return npDurationSeconds; }
    public boolean isNowPlayingHit()           { return npLastHit; }

    /** Simulated last-mile connection capacity — the "true" value the ABR estimate is chasing. */
    public double  getNowPlayingBandwidthKbps()      { return npBandwidthKbps; }
    /** What the ABR logic currently believes the throughput is (EWMA, lags the true value). */
    public double  getNowPlayingThroughputEstimateKbps() { return npThroughputEstKbps; }
    /** Current virtual playback buffer, in seconds of buffered-but-unplayed video. */
    public double  getNowPlayingBufferSeconds()      { return npBufferSeconds; }
    /** The configured buffer target this session is trying to maintain. */
    public double  getBufferTargetSeconds()          { return cfg.bufferTargetSeconds(); }
    /** Buffer level below which ABR forces the lowest bitrate to recover. */
    public double  getBufferPanicThresholdSeconds()  { return Math.max(1.0, cfg.bufferTargetSeconds() * 0.2); }

    /** Snapshot of the last N hit/miss results, oldest first. */
    public List<Boolean> getRecentHistorySnapshot() {
        return new ArrayList<>(recentHits);
    }
}
