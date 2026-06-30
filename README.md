# CDN Picker- The Research Workbench

A Java 21 desktop simulator for exploring CDN cache-replacement strategies
and adaptive-bitrate streaming, with a live Swing UI, real virtual threads,
real embedded video playback, and fully custom-painted charts — no
mock data, no external metrics framework.

---

## Quick start

```bash
cd cdn-picker
./gradlew run             # Linux / macOS
gradlew.bat run           # Windows
```

**Requirements:** Java 21 SDK. First run needs internet access once, to pull
the JavaFX modules (used for embedded video playback) from Maven Central.

---

## Features

| Layer | Detail |
|-------|--------|
| **Cache algorithms** | LRU, LFU, ARC, Clock — real implementations (LinkedHashMap access-order, O(1) frequency buckets, T1/T2/B1/B2 adaptive lists, sweeping reference-bit hand), not labels on the same underlying logic |
| **Predictive caching** | Markov-chain prefetch wrapper around any cache policy, firing prefetch requests on dedicated virtual threads (toggle in UI) |
| **Adaptive bitrate (ABR)** | Per-segment, throughput + buffer hybrid heuristic — see [ABR model](#abr-model-throughput--buffer-hybrid) below |
| **Virtual threads** | Every user session runs on a named `Thread.ofVirtual()` thread (`cdn-user-N`); live count self-reported via an `AtomicInteger`, since `Thread.getAllStackTraces()` can't enumerate virtual threads |
| **Real MP4 streaming** | Load an actual `.mp4`/`.m4v` file; it's parsed (`mvhd` box, FastStart) and divided into byte-range segments that get cached, fetched, and played for real |
| **Embedded video playback** | The Playback tab renders actual decoded frames via JavaFX (`JFXPanel` + `MediaPlayer`), auto-synced to whichever simulated viewer you select |
| **Departure mode** | Optional: viewers leave for good after one pass instead of looping forever; pairs with a configurable early-drop-off percentage |
| **Live theming** | Settings → Theme: background, separator-line color, font color, and font size, applied live across the whole app |
| **Metrics** | Hit rate, avg latency, bandwidth, origin traffic, total requests, and rebuffer/stall events — all computed from real simulated events, not scripted numbers |
| **Cache Visualizer** | LRU/LFU: slot grid with recency/frequency heat; ARC: T1/T2/B1/B2 bars + adaptive-`p` marker; Clock: circular ring with animated hand — all reading live internal cache state |
| **Network Graph** | Origin + edge nodes with load-coloured glow, live user-count badges (reflects departures in real time), animated traffic dots |

---

## The four tabs

**Metrics** — six headline stats (hit rate, latency, bandwidth, origin traffic, total requests, rebuffers) plus a 2×2 grid of rolling charts.

**Cache Visualizer** — pick an edge node and watch its cache's actual internal state: which keys are MRU/LRU, LFU frequency counts, ARC's adaptive `T1`/`T2`/`B1`/`B2` split with the `p` marker sliding, or the Clock hand sweeping past reference bits.

**Network Graph** — the topology: origin in the center, edge nodes in a ring colored by load, animated traffic dots, user counts per node that actually shrink over time if Departure Mode is on.

**Playback** — pick a simulated viewer and see real decoded video frames (if an MP4 is loaded), synced to that viewer's simulated position. Below the video: their current quality tier, last fetch's HIT/MISS, a DEPARTED badge if they've left, the simulated connection bandwidth vs. what the ABR logic currently estimates, a buffer-health bar, and a strip of their last 30 segment fetches.

---

## ABR model (throughput + buffer hybrid)

Bitrate is chosen fresh for every segment, not once per viewing session. Each
simulated viewer has its own last-mile connection (`UserSession.bandwidthKbps`)
that random-walks ±25% per segment — this is what makes segment transfer
time genuinely depend on the chosen bitrate (a 4 Mbps segment really does
take ~10x longer to pull down than a 400 kbps one on the same connection).

The algorithm itself:
1. An EWMA of measured per-segment throughput estimates what the connection
   can sustain (never committing 100% of the estimate — same margin real
   players use).
2. A virtual playback buffer tracks whether downloads are keeping ahead of
   playback; if it drops below ~20% of the configured buffer target, the
   bitrate is forced to the lowest rung regardless of throughput, and an
   empty buffer counts as a rebuffer/stall event.
3. Upward steps are capped at one rung per segment (hysteresis) so a noisy
   estimate doesn't cause oscillation; downward steps are immediate.

This is a simplified hybrid heuristic in the spirit of real ABR algorithms
(throughput-based + buffer-based, similar to what ExoPlayer/hls.js/Shaka
combine), not a reproduction of one specific published algorithm like BOLA.
The Playback tab shows both the simulated ground-truth bandwidth and the
algorithm's lagging estimate side by side — watching them diverge after a
sudden bandwidth change is the actual point of the simulation.

---

## Real MP4 mode notes

- The file must be **FastStart** (`moov` before `mdat`). If you get a parse
  failure, re-encode with `ffmpeg -i in.mp4 -c copy -movflags faststart out.mp4`.
- Segments are equal-size byte slices of the file, not frame-aligned —
  fine for cache-behaviour research, not meant to produce standalone
  playable clips outside this app.
- Cached segment payload is capped at 64 KB regardless of real segment size
  (`VideoFile.MAX_SERVE_BYTES`) to keep memory bounded — `edgeNodes ×
  cacheSize × 64 KB` is the rough ceiling. The sidebar shows a live memory
  estimate (green/amber/red) as you adjust those sliders.
- All simulated viewers stream the *same* loaded file in this mode, which
  is great for confirming the mechanism works and for watching real
  playback, but not a great mode for comparing cache algorithms against
  each other — see Accuracy & Scope below.

---

## Architecture

```
cdn/
  CDNSimulator       — wires everything; start()/stop() lifecycle, named VT factory
  EdgeNode           — one PoP with its own CachePolicy; FetchResult(data, hit)
  OriginServer       — high-latency fallback; Zipf video popularity OR real VideoFile
  VideoFile          — parses mvhd, divides a real MP4 into byte-range segments
  UserSession        — Runnable on a virtual thread; per-segment ABR, buffer/throughput model
  VideoSegment       — videoId/segmentIndex/bitrate/duration; cache-key + size helpers
  SimulationConfig    — all user-configurable knobs (record)

cache/
  CachePolicy        — interface (get/put/getOrderedKeys/getEntryInfo …)
  LRUCache / LFUCache / ARCCache / ClockCache
  PredictiveCache    — wrapper; builds Markov table; prefetches on virtual threads

metrics/
  MetricsCollector   — LongAdder hot path, sliding latency window, rebuffer counter
  MetricsSnapshot    — immutable record handed to the UI each second

ui/
  MainFrame          — JFrame; Settings menu, app icon, 1-second Swing Timer
  Theme              — live-editable background/separator/font color/size
  ThemeSettingsDialog— color pickers + font-size spinner (Settings → Theme)
  ControlPanel       — all knobs; builds SimulationConfig on Start; memory estimate
  MetricsPanel       — headline stats bar + 2×2 TimeSeriesChart grid
  CacheVisualizerPanel — algorithm-aware renderer (grid / bars / clock)
  NetworkGraphPanel  — animated topology; live per-node user counts
  PlaybackPanel      — embedded JavaFX video + connection/buffer/ABR readout
  TimeSeriesChart    — custom Java2D sparkline with area fill + legend
```

---

## Theming

Settings → Theme opens a non-modal dialog with three color swatches
(background, separator lines, font color) and a font-size spinner; changes
apply live. Informational colors — cache hit/miss, quality tiers, network
load, ARC/Clock indicators, Start/Stop button intents — stay fixed on
purpose, since they carry meaning rather than just style.

The window's startup size lives in `MainFrame.java` (`setSize(...)` /
`setMinimumSize(...)`), and the app icon is loaded from
`src/main/java/com/cdnworkbench/CDNpicker.png` (declared as an extra
resources directory in `build.gradle`, since Gradle doesn't bundle
non-`.java` files from `src/main/java` by default).

---

## Tuning tips

- **Speed 10–20x** is most interesting: caches fill up and ABR reacts to
  bandwidth swings within seconds instead of minutes.
- **High user count + small cache** exposes LRU vs. LFU trade-offs clearly.
- **ARC** adapts its `T1`/`T2` split automatically — watch the `p` marker
  shift toward recency or frequency as access patterns change.
- **Predictive caching** improves hit rate most on sequential access
  patterns (shorter segment lengths → more sequential requests).
- **Departure mode + a higher drop-off %** is the way to see load and cache
  pressure visibly taper off over time, rather than holding steady forever.
- **Synthetic mode** (no MP4 loaded) is the more meaningful mode for
  comparing cache algorithms against each other — real-MP4 mode is great
  for watching actual playback, but with only one video's segments, any
  algorithm tends to converge to a similarly high hit rate.

---

## Accuracy & scope

The four cache algorithms are genuine implementations, not simplified
stand-ins — LRU, LFU, and Clock are textbook-correct; ARC captures the
core adaptive mechanism faithfully but has a few boundary-condition
details that deviate from Megiddo & Modha's exact pseudocode, so treat it
as conceptually correct rather than a verified reference implementation.
The visualizations read real internal cache/session state directly, not a
separate "presentation" copy of the data.

The surrounding simulation is intentionally simplified: latency constants
are illustrative, not measured or geography-derived; each edge node is
fully independent with no cache hierarchy, peer sharing, or request
coalescing; and the ABR model (see above) is a hybrid heuristic, not a
named published algorithm. Treat this tool as good for building intuition
and comparing relative trends under a controlled, identical workload
not as a stand-in for production CDN benchmarking or capacity planning.
