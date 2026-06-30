package com.cdnworkbench.ui;

import com.cdnworkbench.cdn.EdgeNode;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.List;

/**
 * Paints the CDN topology: one origin server in the centre, edge nodes
 * arranged in a ring, user-count badges, and animated traffic dots that
 * drift from origin to each edge proportional to load.
 *
 * A 50 ms repaint timer drives the traffic-dot animation. Background/text
 * colors and font sizes are read fresh from Theme.get() on each paint
 * (this panel draws everything via Graphics2D, so it registers
 * Theme.get().addListener(this::repaint) to stay live). Origin/load/user
 * indicator colors stay fixed — they're semantic.
 */
public final class NetworkGraphPanel extends JPanel {

    // ---- Semantic colours (not themeable) ---------------------------------
    private static final Color ORIGIN_CLR  = new Color(255, 166, 77);
    private static final Color EDGE_LOW    = new Color(63,  185,  80);
    private static final Color EDGE_MED    = new Color(255, 200,  50);
    private static final Color EDGE_HIGH   = new Color(255,  75,  75);
    private static final Color LINK_CLR    = new Color(48,  70, 130);
    private static final Color USER_DOT    = new Color(140, 200, 255);

    // ---- State ------------------------------------------------------------
    private List<EdgeNode> edgeNodes = List.of();
    private final JLabel   statsLbl;

    public NetworkGraphPanel() {
        setBackground(Theme.get().backgroundUI());
        setLayout(new BorderLayout());

        // Stats bar
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 5));
        bar.setBackground(Theme.get().headerBackgroundUI());
        Runnable applyBarBorder = () -> bar.setBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.get().separator()));
        applyBarBorder.run();
        Theme.get().addListener(applyBarBorder);
        statsLbl = new JLabel("  No simulation running");
        statsLbl.setForeground(Theme.get().dimFontColorUI());
        statsLbl.setFont(Theme.get().monoFontUI());
        bar.add(statsLbl);
        add(bar, BorderLayout.NORTH);

        // Legend
        add(buildLegend(), BorderLayout.SOUTH);

        // Canvas
        JPanel canvas = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintGraph((Graphics2D) g, getWidth(), getHeight());
            }
        };
        canvas.setBackground(Theme.get().backgroundUI());
        add(canvas, BorderLayout.CENTER);

        // Animation timer (20 fps traffic dots)
        new Timer(50, e -> repaint()).start();

        Theme.get().addListener(this::repaint);
    }

    // ---- Update (called from EDT) ----------------------------------------

    public void update(List<EdgeNode> nodes) {
        this.edgeNodes = nodes;
        int users = nodes.stream().mapToInt(EdgeNode::getActiveUsers).sum();
        statsLbl.setText(String.format("  %d edge node(s)  |  %d virtual user thread(s)  " +
                         "|  link width ∝ load", nodes.size(), users));
        repaint();
    }

    public void reset() {
        edgeNodes = List.of();
        statsLbl.setText("  No simulation running");
        repaint();
    }

    // ---- Paint -----------------------------------------------------------

    private void paintGraph(Graphics2D g2, int w, int h) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Theme theme = Theme.get();
        g2.setColor(theme.background()); g2.fillRect(0, 0, w, h);

        if (edgeNodes.isEmpty()) {
            placeholder(g2, w, h); return;
        }

        int ox = w / 2, oy = h / 2;   // origin centre

        // 1. Links (drawn below nodes)
        for (EdgeNode n : edgeNodes) {
            int nx = (int)(n.graphX * w), ny = (int)(n.graphY * h);
            float load = (float) n.getLoad();
            float lw   = 1.5f + load * 5f;
            g2.setStroke(new BasicStroke(lw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(blendColor(LINK_CLR, loadColor(load), load * 0.5f));
            g2.drawLine(ox, oy, nx, ny);

            // Animated traffic dots (drift from origin toward edge)
            paintTrafficDots(g2, ox, oy, nx, ny, load);
        }
        g2.setStroke(new BasicStroke(1f));

        // 2. Edge nodes
        for (EdgeNode n : edgeNodes) {
            int nx = (int)(n.graphX * w), ny = (int)(n.graphY * h);
            int nr = 30;
            Color nc = loadColor((float) n.getLoad());

            glow(g2, nx, ny, nr + 14, nc);
            g2.setColor(nc.darker().darker()); g2.fillOval(nx-nr, ny-nr, nr*2, nr*2);
            g2.setColor(nc);                   g2.fillOval(nx-nr+4, ny-nr+4, (nr-4)*2, (nr-4)*2);

            // Node ID
            g2.setColor(Color.WHITE);
            g2.setFont(theme.headerFont());
            FontMetrics fm = g2.getFontMetrics();
            String id = "E" + n.getNodeId();
            g2.drawString(id, nx - fm.stringWidth(id)/2, ny + 4);

            // Region label
            g2.setColor(theme.dimFontColor()); g2.setFont(theme.smallFont());
            fm = g2.getFontMetrics();
            g2.drawString(n.getRegion(), nx - fm.stringWidth(n.getRegion())/2, ny + nr + 13);

            // User dots above node
            if (n.getActiveUsers() > 0) {
                paintUserDots(g2, nx, ny - nr - 7, n.getActiveUsers());
            }

            // Load %
            g2.setColor(Color.WHITE); g2.setFont(theme.smallFont());
            String pct = String.format("%.0f%%", n.getLoad() * 100);
            g2.drawString(pct, nx - g2.getFontMetrics().stringWidth(pct)/2, ny + 17);
        }

        // 3. Origin node (on top)
        int or_ = 44;
        glow(g2, ox, oy, or_ + 18, ORIGIN_CLR);
        g2.setColor(ORIGIN_CLR.darker().darker()); g2.fillOval(ox-or_, oy-or_, or_*2, or_*2);
        g2.setColor(ORIGIN_CLR);                   g2.fillOval(ox-or_+5, oy-or_+5, (or_-5)*2, (or_-5)*2);
        g2.setColor(Color.WHITE); g2.setFont(theme.headerFont());
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString("ORIGIN", ox - fm.stringWidth("ORIGIN")/2, oy + 5);
        g2.setColor(theme.dimFontColor()); g2.setFont(theme.smallFont());
        g2.drawString("Server", ox - g2.getFontMetrics().stringWidth("Server")/2, oy + 18);
    }

    private void paintTrafficDots(Graphics2D g2, int x1, int y1, int x2, int y2, float load) {
        if (load < 0.05f) return;
        int dotCount = (int)(load * 4) + 1;
        long phase = System.currentTimeMillis() % 2000;
        for (int i = 0; i < dotCount; i++) {
            double t = ((double) phase / 2000 + (double) i / dotCount) % 1.0;
            int dx = (int)(x1 + (x2 - x1) * t);
            int dy = (int)(y1 + (y2 - y1) * t);
            int alpha = (int)(120 + 80 * Math.sin(t * Math.PI));
            g2.setColor(new Color(160, 210, 255, Math.min(255, alpha)));
            g2.fillOval(dx - 4, dy - 4, 8, 8);
        }
    }

    private void paintUserDots(Graphics2D g2, int cx, int y, int count) {
        Theme theme = Theme.get();
        int max = Math.min(count, 10), spacing = 8;
        int startX = cx - max * spacing / 2;
        g2.setColor(USER_DOT);
        for (int i = 0; i < max; i++) g2.fillOval(startX + i * spacing, y, 5, 5);
        if (count > max) {
            g2.setFont(theme.smallFont());
            g2.drawString("+" + (count - max), startX + max * spacing + 2, y + 5);
        }
        g2.setFont(theme.monoBoldFont());
        g2.setColor(USER_DOT);
        String lbl = count + " users";
        g2.drawString(lbl, cx - g2.getFontMetrics().stringWidth(lbl)/2, y - 3);
    }

    private void glow(Graphics2D g2, int cx, int cy, int outerR, Color c) {
        for (int r = outerR; r > outerR - 14; r -= 3) {
            float a = 0.06f * (outerR - r + 1) / 3f;
            g2.setColor(new Color(c.getRed()/255f, c.getGreen()/255f, c.getBlue()/255f, a));
            g2.fillOval(cx - r, cy - r, r * 2, r * 2);
        }
    }

    private static Color loadColor(float load) {
        if (load < 0.4f) return EDGE_LOW;
        if (load < 0.75f) return blendColor(EDGE_LOW, EDGE_MED, (load - 0.4f) / 0.35f);
        return blendColor(EDGE_MED, EDGE_HIGH, (load - 0.75f) / 0.25f);
    }

    private static Color blendColor(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int)(a.getRed()   + (b.getRed()   - a.getRed())   * t),
                         (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                         (int)(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
    }

    private JPanel buildLegend() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 18, 4));
        p.setBackground(Theme.get().headerBackgroundUI());
        Runnable applyBorder = () -> p.setBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.get().separator()));
        applyBorder.run();
        Theme.get().addListener(applyBorder);
        legendItem(p, ORIGIN_CLR, "Origin");
        legendItem(p, EDGE_LOW,   "Low load");
        legendItem(p, EDGE_MED,   "Med load");
        legendItem(p, EDGE_HIGH,  "High load");
        legendItem(p, USER_DOT,   "User sessions");
        return p;
    }

    private static void legendItem(JPanel p, Color c, String txt) {
        JLabel dot = new JLabel("\u25CF"); dot.setForeground(c);
        dot.setFont(new Font("SansSerif", Font.PLAIN, 15));
        JLabel lbl = new JLabel(txt);
        lbl.setForeground(Theme.get().dimFontColorUI());
        lbl.setFont(Theme.get().smallFontUI());
        p.add(dot); p.add(lbl);
    }

    private static void placeholder(Graphics2D g2, int w, int h) {
        g2.setColor(Theme.get().dimFontColor());
        g2.setFont(Theme.get().bodyFont());
        String msg = "Start the simulation to see the network topology";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
    }
}
