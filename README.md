# CDN Research Workbench

A Java 21 desktop simulator for exploring CDN cache-replacement strategies
with a live Swing UI, real virtual threads, and fully custom charts.

---

## Quick start

```bash
cd cdn-workbench
gradle wrapper            # generate gradlew (needs Gradle 8.x installed once)
./gradlew run             # Linux / macOS
gradlew.bat run           # Windows
```

**Requirements:** Java 21 SDK, Gradle 8+

---

## Features

| Layer | Detail |
|-------|--------|
| **Cache algorithms** | LRU, LFU, ARC, Clock — full implementations, no external libs |
| **Predictive caching** | Markov-chain prefetch on virtual threads (toggle in UI) |
| **Virtual threads** | Every user session runs on a `Thread.ofVirtual()` thread — `Thread.sleep()` inside sessions never blocks a carrier thread |
| **Metrics** | Hit rate, avg latency, bandwidth Mbps, origin traffic Mbps — live rolling charts |
| **Cache Visualizer** | LRU/LFU: slot grid with recency/frequency heat; ARC: T1/T2/B1/B2 bars + adaptive-p marker; Clock: circular ring with animated hand |
| **Network Graph** | Origin + edge nodes with load-coloured glow, user-count badges, animated traffic dots |

---

## Architecture

```
cdn/
  CDNSimulator       — wires everything; start() / stop() lifecycle
  EdgeNode           — one PoP with its own CachePolicy
  OriginServer       — high-latency fallback with Zipf video popularity
  UserSession        — Runnable on a virtual thread; loops streamOneVideo()

cache/
  CachePolicy        — interface (get/put/getOrderedKeys/getEntryInfo …)
  LRUCache / LFUCache / ARCCache / ClockCache
  PredictiveCache    — wrapper; builds Markov table; prefetches on VTs

metrics/
  MetricsCollector   — LongAdder hot path, sliding latency window
  MetricsSnapshot    — immutable record handed to the UI each second

ui/
  MainFrame          — JFrame + 1-second Swing Timer
  ControlPanel       — all knobs; builds SimulationConfig on Start
  MetricsPanel       — headline stats bar + 2×2 TimeSeriesChart grid
  CacheVisualizerPanel — algorithm-aware renderer (grid / bars / clock)
  NetworkGraphPanel  — animated topology; 20 fps repaint timer
  TimeSeriesChart    — custom Java2D sparkline with area fill + legend
```

---

## Tuning tips

- **Speed 10–20x** is most interesting: cache fills up quickly, eviction
  patterns become visible within seconds.
- **High user count + small cache** exposes LRU vs LFU trade-offs clearly.
- **ARC** adapts its T1/T2 split automatically — watch the p-marker shift.
- **Predictive caching** improves hit rate most on sequential access patterns
  (lower segment lengths → more sequential requests).
