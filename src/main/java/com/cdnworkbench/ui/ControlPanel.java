package com.cdnworkbench.ui;

import com.cdnworkbench.cdn.CDNSimulator;
import com.cdnworkbench.cdn.SimulationConfig;
import com.cdnworkbench.cdn.VideoFile;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Left-side control sidebar.
 *
 * Background/separator/font-color/font-size all come from Theme.get() so
 * Settings -> Theme changes apply live. Plain backgrounds/foregrounds/fonts
 * use Theme's UIResource-wrapped getters, which refresh automatically via
 * MainFrame's SwingUtilities.updateComponentTreeUI() call. Borders (the
 * section dividers) bake in a fixed color at creation time, so each one
 * registers its own small Theme listener to rebuild itself on change.
 *
 * Semantic colors (Start=green, Stop=red, Load=blue, memory-estimate
 * green/amber/red, the VT-count green indicator) stay fixed — they convey
 * meaning/intent, not just style, and aren't part of the requested theme.
 */
public final class ControlPanel extends JPanel {

    // Semantic (non-themeable) colors
    static final Color ACCENT = new Color(88,  166, 255);
    static final Color GREEN  = new Color(63,  185,  80);
    static final Color AMBER  = new Color(255, 190,  70);
    static final Color RED    = new Color(255,  90,  90);

    /** Synthetic-mode per-segment cap (matches OriginServer.MAX_SYNTH_BYTES). */
    private static final int SYNTHETIC_SEG_BYTES = 1024;

    private final CDNSimulator sim;
    private final MetricsPanel         metricsPanel;
    private final CacheVisualizerPanel cachePanel;
    private final NetworkGraphPanel    netPanel;
    private final PlaybackPanel        playbackPanel;

    private final JComboBox<String> algoBox = combo("LRU", "LFU", "ARC", "CLOCK");
    private final JCheckBox predictCb = check("Predictive Caching (Markov prefetch)");

    private final JCheckBox cb360  = check("360p   400 kbps",  true);
    private final JCheckBox cb480  = check("480p   800 kbps",  true);
    private final JCheckBox cb720  = check("720p  1.5 Mbps",   true);
    private final JCheckBox cb1080 = check("1080p   4 Mbps",   true);

    private final JSlider segSlider   = slider(1, 10,  4);
    private final JSlider bufSlider   = slider(5, 60, 15);
    private final JSlider edgeSlider  = slider(1,  8,  3);
    private final JSlider userSlider  = slider(1,100, 20);
    private final JSlider cacheSlider = slider(10,500,100);
    private final JSlider speedSlider = slider(1, 20,  5);

    private final JCheckBox departureCb   = check("Departure Mode (viewers leave for good)");
    private final JSlider   dropOffSlider = slider(0, 100, 25);

    private final JButton startBtn  = button("Start",    new Color(35, 110, 55));
    private final JButton stopBtn   = button("Stop",     new Color(130, 40, 40));
    private final JButton resetBtn  = button("Reset",    new Color(50, 50, 80));
    private final JButton loadBtn   = button("Load MP4", new Color(55, 80, 140));

    private JLabel statusLabel;
    private final JLabel videoInfoLabel = dimLabel("  No MP4 loaded  (using synthetic data)");
    private final JLabel vtCountLabel   = dimLabel("  VT threads: –");
    private final JLabel memEstLabel    = dimLabel("");

    private VideoFile loadedVideo = null;

    public ControlPanel(CDNSimulator sim, MetricsPanel mp,
                        CacheVisualizerPanel cp, NetworkGraphPanel np,
                        PlaybackPanel pp) {
        this.sim           = sim;
        this.metricsPanel  = mp;
        this.cachePanel    = cp;
        this.netPanel      = np;
        this.playbackPanel = pp;

        setPreferredSize(new Dimension(262, 0));
        setBackground(Theme.get().backgroundUI());
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setThemedRightBorder(this);

        add(header());
        add(videoSection());
        add(section("Algorithm",       algorithmSection()));
        add(section("Bitrate Ladder",  bitrateSection()));
        add(section("Segment / Buffer",segBufSection()));
        add(section("Network",         networkSection()));
        add(section("Viewer Behavior", viewerBehaviorSection()));
        add(section("Cache",           cacheSection()));
        add(section("Speed",           speedSection()));
        add(Box.createVerticalGlue());
        add(vtSection());
        add(buttonRow());

        stopBtn.setEnabled(false);
        startBtn.addActionListener(e -> doStart());
        stopBtn .addActionListener(e -> doStop());
        resetBtn.addActionListener(e -> doReset());
        loadBtn .addActionListener(e -> doLoadMP4());

        cacheSlider.addChangeListener(e -> updateMemoryEstimate());
        edgeSlider .addChangeListener(e -> updateMemoryEstimate());
        updateMemoryEstimate();
    }

    public void setStatusLabel(JLabel lbl) { this.statusLabel = lbl; }

    public void setVtCount(long count) {
        vtCountLabel.setText(String.format("  \u25CF %d virtual user threads running", count));
        vtCountLabel.setForeground(count > 0 ? GREEN : Theme.get().dimFontColor());
    }

    // ── Memory estimate ──────────────────────────────────────────────────

    private void updateMemoryEstimate() {
        int perSegBytes = (loadedVideo != null) ? VideoFile.MAX_SERVE_BYTES : SYNTHETIC_SEG_BYTES;
        long totalBytes = (long) edgeSlider.getValue() * cacheSlider.getValue() * perSegBytes;
        double mb = totalBytes / 1_048_576.0;

        Color c = mb < 150 ? GREEN : mb < 500 ? AMBER : RED;
        memEstLabel.setForeground(c);
        memEstLabel.setText(String.format("  Est. cache memory: ~%.0f MB", mb));
    }

    // ── Actions ──────────────────────────────────────────────────────────

    private void doLoadMP4() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select an MP4 video file");
        fc.setFileFilter(new FileNameExtensionFilter("MP4 Video (*.mp4, *.m4v)", "mp4", "m4v"));
        fc.setAcceptAllFileFilterUsed(false);

        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();

        try {
            if (loadedVideo != null) { loadedVideo.close(); loadedVideo = null; }
            loadedVideo = VideoFile.load(f, segSlider.getValue());
            sim.setVideoFile(loadedVideo);
            videoInfoLabel.setText(String.format(
                "  \u25B6 %s   %.0f s   %d segs",
                loadedVideo.getName(),
                loadedVideo.getDurationSeconds(),
                loadedVideo.getSegmentCount()));
            videoInfoLabel.setForeground(GREEN);
            updateMemoryEstimate();
            status("  MP4 loaded: " + loadedVideo.getName()
                   + "  (" + loadedVideo.getSegmentCount() + " segments)");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "Could not load MP4:\n" + ex.getMessage(),
                "Load error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doStart() {
        SimulationConfig cfg = buildConfig();
        sim.start(cfg);
        startBtn.setEnabled(false);
        stopBtn .setEnabled(true);
        status("  Running — " + cfg.userCount() + " VT users  |  "
               + cfg.edgeNodeCount() + " edge nodes  |  " + cfg.algorithm()
               + (cfg.predictiveCaching() ? " + Predictive" : "")
               + (loadedVideo != null ? "  |  real MP4" : "  |  synthetic")
               + (cfg.departureMode() ? "  |  Departure mode (" + cfg.dropOffPercent() + "% drop-off)" : "")
               + "  |  " + (int)cfg.simulationSpeed() + "x");
    }

    private void doStop() {
        sim.stop();
        startBtn.setEnabled(true);
        stopBtn .setEnabled(false);
        setVtCount(0);
        status("  Stopped.");
    }

    private void doReset() {
        doStop();
        metricsPanel.reset();
        cachePanel.reset();
        netPanel.reset();
        playbackPanel.reset();
        status("  Ready — configure and click Start.");
    }

    private SimulationConfig buildConfig() {
        List<Integer> rates = new ArrayList<>();
        if (cb360.isSelected())  rates.add(400);
        if (cb480.isSelected())  rates.add(800);
        if (cb720.isSelected())  rates.add(1500);
        if (cb1080.isSelected()) rates.add(4000);
        if (rates.isEmpty())     rates.add(800);
        return new SimulationConfig(
                (String) algoBox.getSelectedItem(),
                segSlider.getValue(),
                rates.stream().mapToInt(Integer::intValue).toArray(),
                bufSlider.getValue(),
                predictCb.isSelected(),
                edgeSlider.getValue(),
                userSlider.getValue(),
                cacheSlider.getValue(),
                speedSlider.getValue(),
                departureCb.isSelected(),
                dropOffSlider.getValue());
    }

    private void status(String msg) {
        if (statusLabel != null) statusLabel.setText(msg);
    }

    // ── UI builders ──────────────────────────────────────────────────────

    private JPanel header() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        p.setBackground(Theme.get().headerBackgroundUI());
        setThemedBottomBorder(p);
        JLabel t = new JLabel("CDN Picker");
        t.setForeground(ACCENT);
        t.setFont(Theme.get().titleFontUI());
        p.add(t);
        Theme.get().addListener(() -> t.setFont(Theme.get().titleFont()));
        return p;
    }

    private JPanel videoSection() {
        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBackground(Theme.get().panelBackgroundUI());
        inner.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));

        loadBtn.setAlignmentX(LEFT_ALIGNMENT);
        loadBtn.setMaximumSize(new Dimension(230, 28));
        inner.add(loadBtn);
        inner.add(Box.createVerticalStrut(4));

        videoInfoLabel.setFont(Theme.get().smallFontUI());
        videoInfoLabel.setAlignmentX(LEFT_ALIGNMENT);
        inner.add(videoInfoLabel);

        return section("Video Source", inner);
    }

    private JPanel vtSection() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.get().backgroundUI());
        setThemedTopBorder(p);
        vtCountLabel.setFont(Theme.get().monoBoldFontUI());
        vtCountLabel.setAlignmentX(LEFT_ALIGNMENT);
        memEstLabel.setFont(Theme.get().monoFontUI());
        memEstLabel.setAlignmentX(LEFT_ALIGNMENT);
        memEstLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        JPanel vtRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        vtRow.setBackground(Theme.get().backgroundUI());
        vtRow.add(vtCountLabel);
        p.add(vtRow);
        p.add(memEstLabel);
        return p;
    }

    private JPanel algorithmSection() {
        JPanel p = row();
        p.add(lbl("Cache Algorithm:")); p.add(algoBox); p.add(predictCb);
        return p;
    }

    private JPanel bitrateSection() {
        JPanel p = new JPanel(new GridLayout(2, 2, 4, 4));
        p.setBackground(Theme.get().panelBackgroundUI());
        p.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
        p.add(cb360); p.add(cb480); p.add(cb720); p.add(cb1080);
        return p;
    }

    private JPanel segBufSection() {
        JPanel p = row();
        p.add(lbl("Segment Length:")); p.add(segSlider);  p.add(valueLabel(segSlider, " s"));
        p.add(lbl("Buffer Target:"));  p.add(bufSlider);  p.add(valueLabel(bufSlider, " s"));
        return p;
    }

    private JPanel networkSection() {
        JPanel p = row();
        p.add(lbl("Edge Nodes:"));       p.add(edgeSlider); p.add(valueLabel(edgeSlider, ""));
        p.add(lbl("Concurrent Users:")); p.add(userSlider); p.add(valueLabel(userSlider, ""));
        return p;
    }

    private JPanel viewerBehaviorSection() {
        JPanel p = row();
        departureCb.setAlignmentX(LEFT_ALIGNMENT);
        p.add(departureCb);
        p.add(Box.createVerticalStrut(4));
        p.add(lbl("Drop-off Chance (real MP4):"));
        p.add(dropOffSlider);
        p.add(valueLabel(dropOffSlider, "%"));
        return p;
    }

    private JPanel cacheSection() {
        JPanel p = row();
        p.add(lbl("Cache (segments):")); p.add(cacheSlider); p.add(valueLabel(cacheSlider, " seg"));
        return p;
    }

    private JPanel speedSection() {
        JPanel p = row();
        p.add(lbl("Speed:")); p.add(speedSlider); p.add(valueLabel(speedSlider, "x"));
        return p;
    }

    /**
     * Builds a titled section. The titled border bakes in the separator
     * color and header font at creation time, so it registers its own
     * listener to rebuild itself whenever the theme changes.
     */
    private JPanel section(String title, JPanel content) {
        JPanel wrap = new JPanel(new BorderLayout());
        Runnable restyle = () -> {
            wrap.setBackground(Theme.get().background());
            wrap.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 6, 0, 6),
                BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(Theme.get().separator(), 1), title,
                    TitledBorder.LEFT, TitledBorder.TOP,
                    Theme.get().headerFont(), Theme.get().dimFontColor())));
        };
        restyle.run();
        Theme.get().addListener(restyle);
        wrap.add(content);
        return wrap;
    }

    private JPanel row() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.get().panelBackgroundUI());
        p.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
        return p;
    }

    private JPanel buttonRow() {
        JPanel p = new JPanel(new GridLayout(1, 3, 4, 0));
        p.setBackground(Theme.get().backgroundUI());
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 10, 8));
        p.add(startBtn); p.add(stopBtn); p.add(resetBtn);
        return p;
    }

    private JLabel valueLabel(JSlider s, String suffix) {
        JLabel l = new JLabel(s.getValue() + suffix, SwingConstants.CENTER);
        l.setForeground(ACCENT);
        l.setFont(Theme.get().monoBoldFontUI());
        l.setAlignmentX(LEFT_ALIGNMENT);
        s.addChangeListener(e -> l.setText(s.getValue() + suffix));
        Theme.get().addListener(() -> l.setFont(Theme.get().monoBoldFont()));
        return l;
    }

    private JLabel lbl(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.get().fontColorUI());
        l.setFont(Theme.get().bodyFontUI());
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel dimLabel(String txt) {
        JLabel l = new JLabel(txt);
        l.setForeground(Theme.get().dimFontColorUI());
        l.setFont(Theme.get().smallFontUI());
        Theme.get().addListener(() -> l.setFont(Theme.get().smallFont()));
        return l;
    }

    private static JSlider slider(int min, int max, int val) {
        JSlider s = new JSlider(min, max, val);
        s.setBackground(Theme.get().panelBackgroundUI());
        s.setForeground(Theme.get().fontColorUI());
        s.setAlignmentX(LEFT_ALIGNMENT); s.setMaximumSize(new Dimension(230, 28));
        return s;
    }

    private static JComboBox<String> combo(String... items) {
        JComboBox<String> b = new JComboBox<>(items);
        b.setBackground(Theme.get().panelBackgroundUI());
        b.setForeground(Theme.get().fontColorUI());
        b.setFont(Theme.get().bodyFontUI());
        b.setAlignmentX(LEFT_ALIGNMENT); b.setMaximumSize(new Dimension(230, 28));
        return b;
    }

    private static JCheckBox check(String text, boolean sel) {
        JCheckBox cb = new JCheckBox(text, sel);
        cb.setBackground(Theme.get().panelBackgroundUI());
        cb.setForeground(Theme.get().fontColorUI());
        cb.setFont(Theme.get().bodyFontUI());
        cb.setAlignmentX(LEFT_ALIGNMENT);
        return cb;
    }

    private static JCheckBox check(String text) { return check(text, false); }

    private static JButton button(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg); b.setForeground(Color.WHITE);
        b.setFont(Theme.get().headerFontUI());
        b.setFocusPainted(false); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        return b;
    }

    // ── Self-refreshing border helpers (separator color isn't UIResource-aware) ─

    private static void setThemedRightBorder(JComponent c) {
        Runnable apply = () -> c.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.get().separator()));
        apply.run();
        Theme.get().addListener(apply);
    }

    private static void setThemedBottomBorder(JComponent c) {
        Runnable apply = () -> c.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.get().separator()));
        apply.run();
        Theme.get().addListener(apply);
    }

    private static void setThemedTopBorder(JComponent c) {
        Runnable apply = () -> c.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.get().separator()));
        apply.run();
        Theme.get().addListener(apply);
    }
}
