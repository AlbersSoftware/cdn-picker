package com.cdnworkbench.ui;

import com.cdnworkbench.cdn.UserSession;
import com.cdnworkbench.cdn.VideoFile;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * Shows live "now playing" state for one selected simulated user session,
 * including a real embedded video preview.
 *
 * Video rendering uses JavaFX (JFXPanel embedded in Swing) since plain
 * Swing has no built-in video codec. This is the one external dependency
 * in the project — added specifically because real frame playback isn't
 * achievable in pure Swing/AWT.
 *
 * All simulated users in real-MP4 mode stream the same loaded file, so a
 * single MediaPlayer is reused across session selections — only the seek
 * position changes. "Auto-sync" mode treats the player as a position-synced
 * still-frame viewer: every UI tick (1s) it seeks to the selected user's
 * current simulated timestamp and pauses, since simulation speed (up to
 * 20x) makes continuous real-time playback meaningless to compare against.
 * Unchecking auto-sync hands control back to the Play/Pause/scrub controls
 * for normal manual playback.
 *
 * Background/separator/font colors and font sizes come from Theme.get();
 * HIT/MISS/quality-tier colors stay fixed (semantic).
 */
public final class PlaybackPanel extends JPanel {

    private static final Color HIT  = new Color(63, 185, 80);
    private static final Color MISS = new Color(255, 166, 77);
    private static final Color BUFFER_LOW = new Color(255, 210, 90);

    private static final Color[] TIER_COLORS = {
        new Color(255, 110, 110),
        new Color(255, 190, 70),
        new Color(110, 180, 255),
        new Color(80,  220, 120),
    };

    private List<UserSession> sessions = List.of();
    private VideoFile         videoFile = null;
    private int                selectedUserId = -1;

    // ── Video preview (JavaFX embedded in Swing) ───────────────────────────
    private final JFXPanel videoFxPanel = new JFXPanel();
    private volatile MediaPlayer mediaPlayer = null;
    private File loadedMediaFile = null;

    private final JButton   playPauseBtn = new JButton("Play");
    private final JButton   muteBtn      = new JButton("Mute");
    private final JCheckBox autoSyncCb   = new JCheckBox("Auto-sync to simulated position", true);
    private final JLabel    videoStatusLabel = dimLabel("  Load an MP4 to enable video preview.");

    private final JComboBox<String> sessionBox = new JComboBox<>(new String[]{"No sessions"});
    private final JButton openBtn = new JButton("Open Source Video in System Player");
    private final JLabel seekHint = dimLabel("");
    private final JPanel canvas;

    public PlaybackPanel() {
        setBackground(Theme.get().backgroundUI());
        setLayout(new BorderLayout());

        // Creating a JFXPanel triggers JavaFX runtime startup automatically.
        Platform.setImplicitExit(false);
        videoFxPanel.setPreferredSize(new Dimension(640, 360));
        videoFxPanel.setBackground(Theme.get().background());

        // Canvas — scrubber, quality badge, hit/miss strip, info text.
        // (Built before the toolbar's listener below, which captures it —
        // a blank final field must be definitely assigned before any
        // lambda referencing it is created.)
        canvas = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintNowPlaying((Graphics2D) g, getWidth(), getHeight());
            }
        };
        canvas.setBackground(Theme.get().backgroundUI());
        canvas.setMinimumSize(new Dimension(300, 310));
        canvas.setPreferredSize(new Dimension(640, 330));

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        toolbar.setBackground(Theme.get().headerBackgroundUI());
        Runnable applyToolbarBorder = () -> toolbar.setBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.get().separator()));
        applyToolbarBorder.run();
        Theme.get().addListener(applyToolbarBorder);
        toolbar.add(dimLabel("Session:"));
        sessionBox.setBackground(Theme.get().panelBackgroundUI());
        sessionBox.setForeground(Theme.get().fontColorUI());
        sessionBox.setFont(Theme.get().bodyFontUI());
        sessionBox.addActionListener(e -> {
            int idx = sessionBox.getSelectedIndex();
            selectedUserId = (idx >= 0 && idx < sessions.size()) ? sessions.get(idx).getUserId() : -1;
            canvas.repaint();
        });
        toolbar.add(sessionBox);
        add(toolbar, BorderLayout.NORTH);

        // Video controls row (under the video preview)
        JPanel videoControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        videoControls.setBackground(Theme.get().headerBackgroundUI());
        playPauseBtn.setEnabled(false);
        muteBtn.setEnabled(false);
        autoSyncCb.setEnabled(false);
        autoSyncCb.setBackground(Theme.get().headerBackgroundUI());
        autoSyncCb.setForeground(Theme.get().fontColorUI());
        autoSyncCb.setFont(Theme.get().bodyFontUI());
        playPauseBtn.addActionListener(e -> togglePlayPause());
        muteBtn.addActionListener(e -> toggleMute());
        videoControls.add(playPauseBtn);
        videoControls.add(muteBtn);
        videoControls.add(autoSyncCb);
        videoControls.add(videoStatusLabel);

        JPanel videoWrapper = new JPanel(new BorderLayout());
        videoWrapper.setBackground(Theme.get().backgroundUI());
        videoWrapper.add(videoFxPanel, BorderLayout.CENTER);
        videoWrapper.add(videoControls, BorderLayout.SOUTH);

        // Video preview on top, stats/strip below, resizable by the user
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, videoWrapper, canvas);
        split.setResizeWeight(0.62);
        split.setDividerSize(4);
        split.setBorder(null);
        split.setBackground(Theme.get().background());
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);
        // Set after the panel has a real size, so the canvas starts with
        // enough room for its content instead of relying on preferred-size
        // guesswork (which previously let the legend text sit right at the
        // canvas's bottom edge, crowding the system-player button below it).
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.55));

        // Bottom: fallback — open in system player
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBackground(Theme.get().headerBackgroundUI());
        Runnable applyBottomBorder = () -> bottom.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.get().separator()),
            BorderFactory.createEmptyBorder(18, 10, 12, 10)));
        applyBottomBorder.run();
        Theme.get().addListener(applyBottomBorder);

        openBtn.setBackground(new Color(55, 80, 140));
        openBtn.setForeground(Color.WHITE);
        openBtn.setFocusPainted(false);
        openBtn.setAlignmentX(LEFT_ALIGNMENT);
        openBtn.setEnabled(false);
        openBtn.addActionListener(e -> openSourceVideo());
        bottom.add(openBtn);
        bottom.add(Box.createVerticalStrut(6));

        seekHint.setAlignmentX(LEFT_ALIGNMENT);
        seekHint.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 0));
        seekHint.setText("  Fallback if the embedded preview won't decode this file's codec.");
        bottom.add(seekHint);

        add(bottom, BorderLayout.SOUTH);

        Theme.get().addListener(() -> {
            videoFxPanel.setBackground(Theme.get().background());
            split.setBackground(Theme.get().background());
            canvas.repaint();
        });
    }

    // ── Update (called from EDT each second) ────────────────────────────────

    public void update(List<UserSession> sessions, VideoFile videoFile) {
        this.sessions  = sessions;
        this.videoFile = videoFile;
        openBtn.setEnabled(videoFile != null);

        if (videoFile != null) {
            File src = videoFile.getSourceFile();
            if (loadedMediaFile == null || !loadedMediaFile.equals(src)) {
                initializeMediaPlayer(src);
            }
        } else if (loadedMediaFile != null) {
            disposeMediaPlayer();
            loadedMediaFile = null;
            Platform.runLater(() -> videoFxPanel.setScene(null));
            videoStatusLabel.setText("  Load an MP4 to enable video preview.");
            setVideoControlsEnabled(false);
        }

        // Rebuild the combo only when the session set size changes
        if (sessionBox.getItemCount() != Math.max(1, sessions.size())) {
            sessionBox.removeAllItems();
            if (sessions.isEmpty()) {
                sessionBox.addItem("No sessions");
                selectedUserId = -1;
            } else {
                for (UserSession s : sessions) {
                    sessionBox.addItem(String.format("User %d  —  %s [Edge %d]",
                        s.getUserId(), s.getEdgeNode().getRegion(), s.getEdgeNode().getNodeId()));
                }
                if (selectedUserId < 0 && !sessions.isEmpty()) {
                    selectedUserId = sessions.get(0).getUserId();
                }
            }
        }

        syncPlayheadIfNeeded(selected());
        canvas.repaint();
    }

    public void reset() {
        sessions = List.of();
        videoFile = null;
        selectedUserId = -1;
        sessionBox.removeAllItems();
        sessionBox.addItem("No sessions");
        openBtn.setEnabled(false);
        seekHint.setText("  Fallback if the embedded preview won't decode this file's codec.");

        disposeMediaPlayer();
        loadedMediaFile = null;
        Platform.runLater(() -> videoFxPanel.setScene(null));
        videoStatusLabel.setText("  Load an MP4 to enable video preview.");
        setVideoControlsEnabled(false);
        playPauseBtn.setText("Play");
        muteBtn.setText("Mute");

        canvas.repaint();
    }

    // ── Video player lifecycle (JavaFX thread for all MediaPlayer calls) ──

    private void initializeMediaPlayer(File file) {
        disposeMediaPlayer();
        loadedMediaFile = file;
        playPauseBtn.setText("Play");
        muteBtn.setText("Mute");
        setVideoControlsEnabled(false);
        videoStatusLabel.setText("  Loading video\u2026");

        Platform.runLater(() -> {
            try {
                Media media = new Media(file.toURI().toString());
                MediaPlayer mp = new MediaPlayer(media);
                mp.setAutoPlay(false);

                MediaView view = new MediaView(mp);
                view.setPreserveRatio(true);
                StackPane root = new StackPane(view);
                root.setStyle("-fx-background-color: #0A0A12;");
                view.fitWidthProperty().bind(root.widthProperty());
                view.fitHeightProperty().bind(root.heightProperty());
                videoFxPanel.setScene(new Scene(root));

                mp.setOnReady(() -> SwingUtilities.invokeLater(() -> {
                    videoStatusLabel.setText("  Ready");
                    setVideoControlsEnabled(true);
                }));
                mp.setOnError(() -> SwingUtilities.invokeLater(() ->
                    videoStatusLabel.setText("  Playback error — codec may be unsupported. "
                        + "Use the system-player fallback below.")));

                this.mediaPlayer = mp;
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                    videoStatusLabel.setText("  Could not load video: " + ex.getMessage()));
            }
        });
    }

    private void disposeMediaPlayer() {
        MediaPlayer old = this.mediaPlayer;
        this.mediaPlayer = null;
        if (old != null) Platform.runLater(old::dispose);
    }

    private void syncPlayheadIfNeeded(UserSession s) {
        MediaPlayer mp = this.mediaPlayer;
        if (mp == null || s == null || s.getNowPlayingSegmentIndex() < 0) return;
        if (!autoSyncCb.isSelected()) return;

        double target = s.getNowPlayingTimestampSeconds();
        Platform.runLater(() -> {
            mp.seek(Duration.seconds(target));
            if (mp.getStatus() == MediaPlayer.Status.PLAYING) mp.pause();
        });
    }

    private void togglePlayPause() {
        MediaPlayer mp = this.mediaPlayer;
        if (mp == null) return;
        if (mp.getStatus() == MediaPlayer.Status.PLAYING) {
            Platform.runLater(mp::pause);
            playPauseBtn.setText("Play");
        } else {
            autoSyncCb.setSelected(false);   // manual playback overrides auto-sync
            Platform.runLater(mp::play);
            playPauseBtn.setText("Pause");
        }
    }

    private void toggleMute() {
        MediaPlayer mp = this.mediaPlayer;
        if (mp == null) return;
        boolean newMute = !mp.isMute();
        Platform.runLater(() -> mp.setMute(newMute));
        muteBtn.setText(newMute ? "Unmute" : "Mute");
    }

    private void setVideoControlsEnabled(boolean enabled) {
        playPauseBtn.setEnabled(enabled);
        muteBtn.setEnabled(enabled);
        autoSyncCb.setEnabled(enabled);
    }

    // ── Other actions ────────────────────────────────────────────────────

    private void openSourceVideo() {
        if (videoFile == null) return;
        try {
            if (!Desktop.isDesktopSupported() ||
                !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException("Desktop integration not available on this system");
            }
            Desktop.getDesktop().open(videoFile.getSourceFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "Could not open the video file:\n" + ex.getMessage(),
                "Open failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private UserSession selected() {
        if (selectedUserId < 0) return null;
        for (UserSession s : sessions) if (s.getUserId() == selectedUserId) return s;
        return null;
    }

    // ── Painting (stats / scrubber / hit-miss strip) ───────────────────────

    private void paintNowPlaying(Graphics2D g2, int w, int h) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Theme theme = Theme.get();
        g2.setColor(theme.background()); g2.fillRect(0, 0, w, h);

        UserSession s = selected();
        if (s == null) {
            placeholder(g2, w, h, "Start the simulation to see live playback state");
            return;
        }

        String  video      = s.getNowPlayingVideoId();
        int     segIdx      = s.getNowPlayingSegmentIndex();
        int     totalSegs   = s.getNowPlayingTotalSegments();
        int     bitrate     = s.getNowPlayingBitrateKbps();
        double  timestamp   = s.getNowPlayingTimestampSeconds();
        double  duration    = s.getNowPlayingDurationSeconds();
        boolean lastHit     = s.isNowPlayingHit();
        boolean active      = s.isActive();
        List<Boolean> hist  = s.getRecentHistorySnapshot();

        if (segIdx < 0) {
            placeholder(g2, w, h, "Waiting for first segment request\u2026");
            return;
        }

        int pad = 24, y = 26;

        g2.setFont(theme.headerFont());
        g2.setColor(theme.fontColor());
        g2.drawString("User " + s.getUserId() + "  —  "
                       + s.getEdgeNode().getRegion() + " [Edge " + s.getEdgeNode().getNodeId() + "]"
                       + "    " + video + (videoFile != null ? "  (real MP4)" : "  (synthetic)"), pad, y);
        y += 26;

        // Quality badge
        int tier = tierIndex(bitrate);
        Color tierColor = TIER_COLORS[tier];
        String tierLabel = tierLabel(bitrate);
        g2.setColor(tierColor);
        g2.fillRoundRect(pad, y, 80, 24, 6, 6);
        g2.setColor(Color.BLACK);
        g2.setFont(theme.headerFont());
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(tierLabel, pad + (80 - fm.stringWidth(tierLabel)) / 2, y + 16);

        // Last fetch HIT/MISS badge
        int hx = pad + 90;
        Color hc = lastHit ? HIT : MISS;
        g2.setColor(hc);
        g2.fillRoundRect(hx, y, 64, 24, 6, 6);
        g2.setColor(Color.BLACK);
        String hLbl = lastHit ? "HIT" : "MISS";
        fm = g2.getFontMetrics();
        g2.drawString(hLbl, hx + (64 - fm.stringWidth(hLbl)) / 2, y + 16);

        if (!active) {
            int dx = hx + 74;
            g2.setColor(new Color(90, 90, 120));
            g2.fillRoundRect(dx, y, 100, 24, 6, 6);
            g2.setColor(Color.WHITE);
            String dLbl = "DEPARTED";
            fm = g2.getFontMetrics();
            g2.drawString(dLbl, dx + (100 - fm.stringWidth(dLbl)) / 2, y + 16);
        }
        y += 44;

        // Connection / ABR state — this is the actual mechanism driving
        // bitrate choice: a simulated last-mile connection (ground truth, not
        // visible to a real player) vs. the lagging EWMA estimate the ABR
        // logic actually reacts to, plus virtual buffer health.
        double bwKbps    = s.getNowPlayingBandwidthKbps();
        double estKbps   = s.getNowPlayingThroughputEstimateKbps();
        double bufSec    = s.getNowPlayingBufferSeconds();
        double bufTarget = s.getBufferTargetSeconds();
        double panicSec  = s.getBufferPanicThresholdSeconds();

        g2.setFont(theme.smallFont());
        g2.setColor(theme.dimFontColor());
        g2.drawString(String.format(
            "Sim. connection: %.0f kbps   |   ABR throughput estimate: %.0f kbps   |   Current bitrate: %d kbps",
            bwKbps, estKbps, bitrate), pad, y);
        y += 16;

        g2.drawString(String.format("Buffer: %.1fs  (target %.0fs, forces lowest rung below %.1fs)",
            bufSec, bufTarget, panicSec), pad, y);
        y += 8;

        int bufBarW = w - 2 * pad, bufBarH = 10;
        double bufScaleMax = Math.max(Math.max(bufTarget * 1.5, panicSec * 3), bufSec);
        double bufFrac = bufScaleMax > 0 ? Math.min(1.0, bufSec / bufScaleMax) : 0;
        Color bufColor = bufSec < panicSec ? MISS : (bufSec < bufTarget ? BUFFER_LOW : HIT);
        g2.setColor(theme.panelBackground());
        g2.fillRoundRect(pad, y, bufBarW, bufBarH, 5, 5);
        g2.setColor(bufColor);
        g2.fillRoundRect(pad, y, Math.max(6, (int)(bufBarW * bufFrac)), bufBarH, 5, 5);
        // Panic-threshold marker
        int panicX = pad + (int)(bufBarW * Math.min(1.0, panicSec / Math.max(bufScaleMax, 0.01)));
        g2.setColor(Color.WHITE);
        g2.drawLine(panicX, y - 2, panicX, y + bufBarH + 2);
        y += bufBarH + 20;

        // Timestamp scrubber
        g2.setFont(theme.smallFont());
        g2.setColor(theme.dimFontColor());
        g2.drawString(String.format("%s  /  %s    (segment %d of %d)",
            formatTime(timestamp), formatTime(duration), segIdx + 1, totalSegs), pad, y);
        y += 8;

        int barW = w - 2 * pad, barH = 12;
        double frac = duration > 0 ? Math.min(1.0, timestamp / duration) : 0;
        g2.setColor(theme.panelBackground());
        g2.fillRoundRect(pad, y, barW, barH, 6, 6);
        g2.setColor(new Color(88, 166, 255));
        g2.fillRoundRect(pad, y, Math.max(8, (int)(barW * frac)), barH, 6, 6);
        int phX = pad + (int)(barW * frac);
        g2.setColor(Color.WHITE);
        g2.fillOval(phX - 4, y + barH / 2 - 4, 8, 8);
        y += barH + 24;

        // Hit/miss history strip
        g2.setFont(theme.smallFont());
        g2.setColor(theme.dimFontColor());
        g2.drawString("Recent segment fetches  (oldest \u2192 newest):", pad, y);
        y += 8;

        int sqSize = 14, sqGap = 3;
        int sx = pad;
        for (Boolean hit : hist) {
            g2.setColor(hit ? HIT : MISS);
            g2.fillRoundRect(sx, y, sqSize, sqSize, 3, 3);
            sx += sqSize + sqGap;
        }
        if (hist.isEmpty()) {
            g2.setColor(theme.dimFontColor());
            g2.drawString("(no segments fetched yet)", pad, y + 11);
        }
        y += sqSize + 18;

        legend(g2, pad,       y, HIT,  "Cache hit (edge)");
        legend(g2, pad + 170, y, MISS, "Cache miss (origin)");
    }

    private static int tierIndex(int kbps) {
        if (kbps <= 400)  return 0;
        if (kbps <= 800)  return 1;
        if (kbps <= 1500) return 2;
        return 3;
    }

    private static String tierLabel(int kbps) {
        if (kbps <= 400)  return "360p";
        if (kbps <= 800)  return "480p";
        if (kbps <= 1500) return "720p";
        return "1080p";
    }

    private static String formatTime(double seconds) {
        int s = (int) Math.max(0, seconds);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private static void legend(Graphics2D g2, int x, int y, Color c, String label) {
        g2.setColor(c); g2.fillRoundRect(x, y - 9, 14, 10, 3, 3);
        g2.setColor(Theme.get().fontColor()); g2.setFont(Theme.get().smallFont());
        g2.drawString(label, x + 18, y);
    }

    private static void placeholder(Graphics2D g2, int w, int h, String msg) {
        g2.setColor(Theme.get().dimFontColor());
        g2.setFont(Theme.get().bodyFont());
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
    }

    private static JLabel dimLabel(String txt) {
        JLabel l = new JLabel(txt);
        l.setForeground(Theme.get().dimFontColorUI());
        l.setFont(Theme.get().smallFontUI());
        return l;
    }
}
