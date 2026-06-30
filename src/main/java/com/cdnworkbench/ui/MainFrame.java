package com.cdnworkbench.ui;

import com.cdnworkbench.cdn.CDNSimulator;
import com.cdnworkbench.metrics.MetricsSnapshot;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Top-level Swing window.
 *
 * STARTUP WINDOW SIZE lives here: see setMinimumSize()/setSize() near the
 * end of the constructor. (setPreferredSize() on a JFrame is NOT honored
 * by pack() — that override only applies to JComponent, and JFrame isn't
 * one — so sizing is done explicitly instead.)
 *
 * Adds a Settings menu (top menu bar) -> Theme... which opens
 * ThemeSettingsDialog. Theme changes are propagated two ways: standard
 * Swing components that used Theme's UIResource-wrapped colors/fonts pick
 * up changes automatically via SwingUtilities.updateComponentTreeUI();
 * custom-painted panels (charts, cache visualizer, network graph,
 * playback canvas) and anything using a Border baked with the separator
 * color register their own small Theme listeners to refresh themselves.
 */
public final class MainFrame extends JFrame {

    private final CDNSimulator         sim      = new CDNSimulator();
    private final MetricsPanel         metrics  = new MetricsPanel();
    private final CacheVisualizerPanel cacheViz = new CacheVisualizerPanel();
    private final NetworkGraphPanel    netGraph = new NetworkGraphPanel();
    private final PlaybackPanel        playback = new PlaybackPanel();
    private final ControlPanel         controls = new ControlPanel(sim, metrics, cacheViz, netGraph, playback);

    private final Timer       snapshotTimer;
    private final JLabel      statusBar = new JLabel("  Ready — load an MP4 (optional) then click Start.");
    private final JTabbedPane tabs      = new JTabbedPane();

    public MainFrame() {
        super("CDN Picker - The Research Workbench");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        seedUIManagerDefaults();

        Image appIcon = loadAppIcon();
        if (appIcon != null) {
            setIconImage(appIcon);
            trySetTaskbarIcon(appIcon);
        }

        setJMenuBar(buildMenuBar());

        JScrollPane controlsScroll = new JScrollPane(controls,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        controlsScroll.setBorder(null);
        controlsScroll.getViewport().setBackground(Theme.get().backgroundUI());
        controlsScroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, controlsScroll, buildRightPanel());
        split.setDividerLocation(280);
        split.setDividerSize(4);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        statusBar.setOpaque(true);
        statusBar.setBackground(Theme.get().headerBackgroundUI());
        statusBar.setForeground(Theme.get().dimFontColorUI());
        statusBar.setFont(Theme.get().monoFontUI());
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.get().separator()),
                BorderFactory.createEmptyBorder(2, 0, 2, 0)));
        add(statusBar, BorderLayout.SOUTH);
        controls.setStatusLabel(statusBar);

        snapshotTimer = new Timer(1_000, e -> refreshUI());
        snapshotTimer.start();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                sim.stop();
                snapshotTimer.stop();
                if (sim.getVideoFile() != null) {
                    try { sim.getVideoFile().close(); } catch (Exception ignored) {}
                }
            }
        });

        // Re-tint chrome that isn't auto-refreshed by updateComponentTreeUI
        // (borders aren't covered by that mechanism) whenever the theme
        // changes, then push the new UIManager defaults into every
        // standard Swing component.
        Theme.get().addListener(() -> {
            seedUIManagerDefaults();
            statusBar.setBackground(Theme.get().headerBackgroundUI());
            statusBar.setForeground(Theme.get().dimFontColorUI());
            statusBar.setFont(Theme.get().monoFontUI());
            statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.get().separator()),
                BorderFactory.createEmptyBorder(2, 0, 2, 0)));
            controlsScroll.getViewport().setBackground(Theme.get().backgroundUI());
            tabs.setBackground(Theme.get().backgroundUI());
            tabs.setForeground(Theme.get().fontColorUI());
            tabs.setFont(Theme.get().bodyFontUI());
            SwingUtilities.updateComponentTreeUI(this);
            repaint();
        });

        // ── Startup window size ──────────────────────────────────────────
        setMinimumSize(new Dimension(1180, 1180));
        setSize(1480, 1480);
        setLocationRelativeTo(null);
    }

    /**
     * Loads the application icon from the classpath. CDNpicker.png lives at
     * src/main/java/com/cdnworkbench/CDNpicker.png — see build.gradle's
     * sourceSets block, which is what makes that path resolvable here at
     * all (image files in src/main/java aren't bundled by default).
     */
    private static Image loadAppIcon() {
        java.net.URL url = MainFrame.class.getResource("/com/cdnworkbench/CDNpicker.png");
        if (url == null) {
            System.err.println("[CDN] App icon not found on classpath at "
                + "/com/cdnworkbench/CDNpicker.png — falling back to the default icon.");
            return null;
        }
        return new ImageIcon(url).getImage();
    }

    /** Best-effort: also sets the OS taskbar/dock icon where the platform supports it. */
    private static void trySetTaskbarIcon(Image icon) {
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(icon);
                }
            }
        } catch (Exception ignored) {
            // Not supported on this platform/JVM — the window icon is already set.
        }
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu settingsMenu = new JMenu("Settings");
        JMenuItem themeItem = new JMenuItem("Theme...");
        themeItem.addActionListener(e -> new ThemeSettingsDialog(this).setVisible(true));
        settingsMenu.add(themeItem);
        bar.add(settingsMenu);
        return bar;
    }

    private JPanel buildRightPanel() {
        tabs.setBackground(Theme.get().backgroundUI());
        tabs.setForeground(Theme.get().fontColorUI());
        tabs.setFont(Theme.get().bodyFontUI());
        tabs.addTab("\u25A0  Metrics",          metrics);
        tabs.addTab("\u25A4  Cache Visualizer",  cacheViz);
        tabs.addTab("\u25B2  Network Graph",     netGraph);
        tabs.addTab("\u25B6  Playback",          playback);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(Theme.get().backgroundUI());
        wrap.add(tabs);
        return wrap;
    }

    private void refreshUI() {
        if (sim.isRunning()) {
            MetricsSnapshot snap = sim.takeSnapshot();
            metrics.update(snap);
            cacheViz.update(sim.getEdgeNodes());
            netGraph.update(sim.getEdgeNodes());
            playback.update(sim.getSessions(), sim.getVideoFile());
            controls.setVtCount(sim.countLiveVirtualThreads());
        }
    }

    /** Seeds Swing's UIManager defaults from the current Theme. */
    private static void seedUIManagerDefaults() {
        Theme t = Theme.get();
        Color bg   = t.background();
        Color fg   = t.fontColor();
        Color sel  = t.panelBackground();
        Color ctrl = t.headerBackground();
        Font  body = t.bodyFont();

        UIManager.put("Panel.background",              bg);
        UIManager.put("Label.foreground",               fg);
        UIManager.put("Label.font",                     body);
        UIManager.put("Button.background",              ctrl);
        UIManager.put("Button.foreground",               fg);
        UIManager.put("Button.font",                     body);
        UIManager.put("ComboBox.background",            ctrl);
        UIManager.put("ComboBox.foreground",             fg);
        UIManager.put("ComboBox.font",                   body);
        UIManager.put("ComboBox.selectionBackground",   sel);
        UIManager.put("ComboBox.selectionForeground",    fg);
        UIManager.put("Slider.background",              bg);
        UIManager.put("Slider.foreground",               fg);
        UIManager.put("CheckBox.background",            bg);
        UIManager.put("CheckBox.foreground",             fg);
        UIManager.put("CheckBox.font",                   body);
        UIManager.put("TabbedPane.background",          bg);
        UIManager.put("TabbedPane.foreground",           fg);
        UIManager.put("TabbedPane.font",                 body);
        UIManager.put("TabbedPane.selected",            sel);
        UIManager.put("TabbedPane.shadow",              t.separator());
        UIManager.put("SplitPane.background",           bg);
        UIManager.put("SplitPaneDivider.background",    t.separator());
        UIManager.put("ScrollPane.background",          bg);
        UIManager.put("ScrollBar.background",           ctrl);
        UIManager.put("FileChooser.background",         bg);
        UIManager.put("OptionPane.background",          bg);
        UIManager.put("OptionPane.messageForeground",   fg);
        UIManager.put("MenuBar.background",             ctrl);
        UIManager.put("MenuBar.foreground",              fg);
        UIManager.put("Menu.background",                ctrl);
        UIManager.put("Menu.foreground",                 fg);
        UIManager.put("Menu.font",                       body);
        UIManager.put("MenuItem.background",            ctrl);
        UIManager.put("MenuItem.foreground",             fg);
        UIManager.put("MenuItem.font",                   body);
        UIManager.put("Spinner.background",             ctrl);
        UIManager.put("Spinner.foreground",              fg);
    }
}
