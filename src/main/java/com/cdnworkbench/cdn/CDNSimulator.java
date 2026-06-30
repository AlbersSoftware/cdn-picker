package com.cdnworkbench.cdn;

import com.cdnworkbench.cache.*;
import com.cdnworkbench.metrics.MetricsCollector;
import com.cdnworkbench.metrics.MetricsSnapshot;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central simulation engine.
 *
 * Virtual threads:
 *   All UserSession tasks run on named virtual threads created via
 *   Thread.ofVirtual().name("cdn-user-", 0).factory()
 *   and Executors.newThreadPerTaskExecutor(factory).
 *
 *   Prefetch threads (in PredictiveCache) use Thread.ofVirtual() directly.
 *
 *   IMPORTANT: Thread.getAllStackTraces() does NOT enumerate virtual
 *   threads — it only walks platform-thread ThreadGroups, by design (the
 *   JVM is built to support millions of virtual threads, so there is no
 *   cheap global enumeration API for them). Counting live sessions instead
 *   uses a self-reported AtomicInteger, incremented/decremented by each
 *   UserSession at the start/end of its run() — this is the standard
 *   pattern for tracking virtual-thread-backed task counts.
 *
 * VideoFile:
 *   Call setVideoFile() before or after start().  The reference is volatile
 *   so running sessions will pick it up on their next video request.
 *
 * Memory:
 *   Cached payload per segment is capped at VideoFile.MAX_SERVE_BYTES
 *   (64 KB) — see VideoFile's class doc for why.  Worst-case cache memory
 *   is roughly edgeNodeCount * cacheSizeSegments * 64 KB.
 */
public final class CDNSimulator {

    private static final String[] REGIONS = {
        "US-East", "US-West", "EU-West", "EU-Central",
        "AP-Tokyo", "AP-Singapore", "SA-Brazil", "AU-Sydney"
    };

    private final MetricsCollector  metrics   = new MetricsCollector();
    private final List<EdgeNode>    edgeNodes = new ArrayList<>();
    private final List<UserSession> sessions  = new ArrayList<>();

    /** Self-reported count of currently-running UserSession virtual threads. */
    private final AtomicInteger liveSessionCount = new AtomicInteger(0);

    private ExecutorService vtPool;
    private OriginServer    origin;
    private volatile VideoFile videoFile  = null;
    private volatile boolean   running    = false;
    private SimulationConfig   config;

    // ── VideoFile ──────────────────────────────────────────────────────────

    public void setVideoFile(VideoFile vf) {
        this.videoFile = vf;
        if (origin != null) origin.setVideoFile(vf);
    }

    public VideoFile getVideoFile() { return videoFile; }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    public void start(SimulationConfig cfg) {
        if (running) stop();

        this.config = cfg;
        this.metrics.reset();
        this.liveSessionCount.set(0);
        this.origin = new OriginServer(metrics);
        this.origin.setVideoFile(videoFile);

        edgeNodes.clear();
        for (int i = 0; i < cfg.edgeNodeCount(); i++) {
            double angle = 2 * Math.PI * i / cfg.edgeNodeCount() - Math.PI / 2;
            double gx = 0.5 + 0.37 * Math.cos(angle);
            double gy = 0.5 + 0.37 * Math.sin(angle);
            CachePolicy cache = buildCache(cfg);
            edgeNodes.add(new EdgeNode(
                i, REGIONS[i % REGIONS.length], cache, origin, metrics, gx, gy));
        }

        int[] perNode = new int[edgeNodes.size()];
        for (int u = 0; u < cfg.userCount(); u++) perNode[u % edgeNodes.size()]++;
        for (int i = 0; i < edgeNodes.size(); i++) edgeNodes.get(i).setActiveUsers(perNode[i]);

        ThreadFactory vtFactory = Thread.ofVirtual()
                                        .name("cdn-user-", 0)
                                        .factory();
        vtPool = Executors.newThreadPerTaskExecutor(vtFactory);

        long estCacheBytes = (long) cfg.edgeNodeCount() * cfg.cacheSizeSegments()
                             * (videoFile != null ? VideoFile.MAX_SERVE_BYTES : 1024);
        String mode = (videoFile != null)
            ? "real MP4 \"" + videoFile.getName() + "\" (" + videoFile.getSegmentCount() + " segs)"
            : "synthetic Zipf catalogue";
        System.out.printf("%n[CDN] Starting simulation%n");
        System.out.printf("[CDN]  Algorithm    : %s%s%n",
            cfg.algorithm(), cfg.predictiveCaching() ? " + Predictive" : "");
        System.out.printf("[CDN]  Video source : %s%n", mode);
        System.out.printf("[CDN]  Users        : %d  (each on a named virtual thread)%n",
            cfg.userCount());
        System.out.printf("[CDN]  Edge nodes   : %d%n", cfg.edgeNodeCount());
        System.out.printf("[CDN]  Cache/node   : %d segments  (~%.1f MB total cache)%n",
            cfg.cacheSizeSegments(), estCacheBytes / 1_048_576.0);
        System.out.printf("[CDN]  Speed        : %.0fx%n", cfg.simulationSpeed());
        System.out.printf("[CDN]  VT factory   : %s%n%n", vtFactory.getClass().getSimpleName());

        sessions.clear();
        for (int u = 0; u < cfg.userCount(); u++) {
            EdgeNode node = edgeNodes.get(u % edgeNodes.size());
            UserSession s = new UserSession(u, node, metrics, cfg, videoFile, liveSessionCount);
            sessions.add(s);
            vtPool.submit(s);
        }

        running = true;
    }

    public void stop() {
        running = false;
        sessions.forEach(UserSession::stop);
        if (vtPool != null) {
            vtPool.shutdownNow();
            try { vtPool.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        sessions.clear();
        edgeNodes.clear();
    }

    // ── Cache factory ──────────────────────────────────────────────────────

    private CachePolicy buildCache(SimulationConfig cfg) {
        CachePolicy base = switch (cfg.algorithm()) {
            case "LFU"   -> new LFUCache  (cfg.cacheSizeSegments());
            case "ARC"   -> new ARCCache  (cfg.cacheSizeSegments());
            case "CLOCK" -> new ClockCache(cfg.cacheSizeSegments());
            default      -> new LRUCache  (cfg.cacheSizeSegments());
        };
        if (!cfg.predictiveCaching()) return base;

        final OriginServer src = this.origin;
        return new PredictiveCache(base, key -> {
            try {
                String[] c  = key.split(":", 2);
                String[] at = c[1].split("@", 2);
                VideoSegment seg = new VideoSegment(
                        c[0], Integer.parseInt(at[0]),
                        Integer.parseInt(at[1]), cfg.segmentLengthSeconds());
                return src.fetchSegment(seg, cfg.simulationSpeed());
            } catch (Exception e) { return null; }
        });
    }

    // ── Live virtual-thread count ────────────────────────────────────────

    /** Number of UserSession virtual threads currently alive and running. */
    public long countLiveVirtualThreads() {
        return liveSessionCount.get();
    }

    // ── Queries (EDT-safe) ─────────────────────────────────────────────────

    public MetricsSnapshot       takeSnapshot() { return metrics.takeSnapshot();   }
    public List<MetricsSnapshot> getHistory()   { return metrics.getHistory();     }
    public List<EdgeNode> getEdgeNodes() {
        // Recompute live active-user counts per node from session state so
        // the network graph reflects departures in real time (only matters
        // when departureMode is on; in continuous mode every session stays
        // active, so this just reproduces the original static distribution).
        if (!sessions.isEmpty()) {
            Map<Integer, Integer> counts = new HashMap<>();
            for (UserSession s : sessions) {
                if (s.isActive()) counts.merge(s.getEdgeNode().getNodeId(), 1, Integer::sum);
            }
            for (EdgeNode n : edgeNodes) n.setActiveUsers(counts.getOrDefault(n.getNodeId(), 0));
        }
        return Collections.unmodifiableList(edgeNodes);
    }
    public List<UserSession>     getSessions()  { return new ArrayList<>(sessions); }
    public MetricsCollector      getMetrics()   { return metrics;                  }
    public boolean               isRunning()    { return running;                  }
    public SimulationConfig      getConfig()    { return config;                   }
}
