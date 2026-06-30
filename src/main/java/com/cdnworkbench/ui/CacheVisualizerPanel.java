package com.cdnworkbench.ui;

import com.cdnworkbench.cache.*;
import com.cdnworkbench.cdn.EdgeNode;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * Renders the live state of a selected edge node's cache.
 *
 *  LRU / LFU  →  horizontal slot grid  (MRU=bright-left, LRU=dim-right;
 *                                        LFU slots tinted by frequency)
 *  ARC        →  stacked bar chart for T1 / T2 / B1 / B2 + adaptive-p marker
 *  CLOCK      →  circular slot ring with animated clock hand
 *
 * Background/text colors and font sizes are read fresh from Theme.get()
 * on each paint (this panel draws everything via Graphics2D, bypassing
 * Swing's background/foreground properties, so the UIResource
 * auto-refresh mechanism other components use doesn't apply here — a
 * Theme.get().addListener(canvas::repaint) keeps it live instead).
 * Slot/ARC/Clock indicator colors stay fixed — they're semantic.
 */
public final class CacheVisualizerPanel extends JPanel {

    // ---- Semantic colours (not themeable) ---------------------------------
    private static final Color SLOT_EMPTY  = new Color(28, 28, 50);
    private static final Color SLOT_MRU    = new Color(63, 185, 80);    // recently used / high-freq
    private static final Color SLOT_FILL   = new Color(55, 110, 190);   // general "cached"
    private static final Color SLOT_LFU_HI = new Color(255, 160, 60);   // high-frequency tint
    private static final Color ARC_T1      = new Color(88, 166, 255);
    private static final Color ARC_T2      = new Color(63, 185, 80);
    private static final Color ARC_GHOST   = new Color(55, 55, 85);
    private static final Color CLK_REF     = new Color(255, 195, 50);
    private static final Color CLK_HAND    = new Color(255, 70,  70);

    // ---- State ------------------------------------------------------------
    private List<EdgeNode> edgeNodes = List.of();
    private int            selected  = 0;

    // ---- Widgets ----------------------------------------------------------
    private final JComboBox<String> nodeBox  = new JComboBox<>(new String[]{"Node 0"});
    private final JLabel            infoLbl  = dimLabel("  |  Waiting for simulation...");
    private final JPanel            canvas;

    public CacheVisualizerPanel() {
        setBackground(Theme.get().backgroundUI());
        setLayout(new BorderLayout());

        // Top toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        toolbar.setBackground(Theme.get().headerBackgroundUI());
        Runnable applyToolbarBorder = () -> toolbar.setBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.get().separator()));
        applyToolbarBorder.run();
        Theme.get().addListener(applyToolbarBorder);
        toolbar.add(dimLabel("Edge Node:"));
        nodeBox.setBackground(Theme.get().panelBackgroundUI());
        nodeBox.setForeground(Theme.get().fontColorUI());
        nodeBox.setFont(Theme.get().bodyFontUI());
        nodeBox.addActionListener(e -> { selected = Math.max(0, nodeBox.getSelectedIndex()); repaint(); });
        toolbar.add(nodeBox);
        toolbar.add(infoLbl);
        add(toolbar, BorderLayout.NORTH);

        // Drawing canvas
        canvas = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintCache((Graphics2D) g, getWidth(), getHeight());
            }
        };
        canvas.setBackground(Theme.get().backgroundUI());
        add(canvas, BorderLayout.CENTER);

        Theme.get().addListener(canvas::repaint);
    }

    // ---- Update -----------------------------------------------------------

    public void update(List<EdgeNode> nodes) {
        this.edgeNodes = nodes;
        if (nodeBox.getItemCount() != nodes.size()) {
            nodeBox.removeAllItems();
            for (EdgeNode n : nodes) nodeBox.addItem(n.getRegion() + "  [Node " + n.getNodeId() + "]");
            selected = 0;
        }
        if (!nodes.isEmpty() && selected < nodes.size()) {
            CachePolicy c = nodes.get(selected).getCache();
            infoLbl.setText(String.format("  |  %s  |  %d / %d slots  |  evictions: %d",
                    c.getName(), c.size(), c.capacity(), c.getEvictionCount()));
        }
        canvas.repaint();
    }

    public void reset() {
        edgeNodes = List.of();
        nodeBox.removeAllItems(); nodeBox.addItem("Node 0");
        infoLbl.setText("  |  Waiting for simulation...");
        canvas.repaint();
    }

    // ---- Painting ---------------------------------------------------------

    private void paintCache(Graphics2D g2, int w, int h) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Theme theme = Theme.get();
        g2.setColor(theme.background()); g2.fillRect(0, 0, w, h);

        if (edgeNodes.isEmpty() || selected >= edgeNodes.size()) {
            placeholder(g2, w, h, "Start the simulation to see cache state"); return;
        }

        CachePolicy raw   = unwrap(edgeNodes.get(selected).getCache());
        String      algo  = raw.getName();

        switch (algo) {
            case "CLOCK" -> { if (raw instanceof ClockCache cc) drawClock(g2, w, h, cc);
                              else drawGrid(g2, w, h, raw); }
            case "ARC"   -> { if (raw instanceof ARCCache  ac) drawARC  (g2, w, h, ac);
                              else drawGrid(g2, w, h, raw); }
            default      -> drawGrid(g2, w, h, raw);
        }
    }

    // ---- Slot grid (LRU / LFU) -------------------------------------------

    private void drawGrid(Graphics2D g2, int w, int h, CachePolicy cache) {
        Theme theme = Theme.get();
        List<String> keys = cache.getOrderedKeys();
        int cap  = cache.capacity();
        int cols = Math.min(cap, 32);
        int rows = (int) Math.ceil((double) cap / cols);

        int pad  = 20, topY = 65;
        int sw   = Math.max(6, (w - 2*pad) / cols - 2);
        int sh   = Math.max(6, Math.min(28, (h - topY - 30) / rows - 2));
        boolean isLFU = "LFU".equals(cache.getName());

        // Title
        g2.setFont(theme.headerFont());
        g2.setColor(theme.fontColor());
        g2.drawString(cache.getName() + " Cache — " + cache.size() + " / " + cap + " slots", pad, 22);

        // Legend
        int lx = pad, ly = 44;
        legend(g2, lx,      ly, SLOT_MRU,   isLFU ? "High freq" : "MRU / recent");
        legend(g2, lx+140,  ly, SLOT_FILL,  "Cached");
        legend(g2, lx+230,  ly, SLOT_EMPTY, "Empty");
        if (isLFU) legend(g2, lx+300, ly, SLOT_LFU_HI, "Freq > 8");

        // Slots
        for (int i = 0; i < cap; i++) {
            int col = i % cols, row = i / cols;
            int x = pad + col * (sw + 2), y = topY + row * (sh + 2);

            Color slotColor;
            if (i < keys.size()) {
                String key = keys.get(i);
                if (isLFU) {
                    Map<String, Object> info = cache.getEntryInfo(key);
                    int freq = (int) info.getOrDefault("frequency", 1);
                    float t  = Math.min(1f, freq / 10f);
                    slotColor = blend(SLOT_FILL, SLOT_LFU_HI, t);
                } else {
                    float t = 1f - (float) i / Math.max(1, keys.size() - 1);
                    slotColor = blend(SLOT_FILL, SLOT_MRU, t * 0.6f);
                }
            } else {
                slotColor = SLOT_EMPTY;
            }

            g2.setColor(slotColor);
            g2.fillRoundRect(x, y, sw, sh, 3, 3);

            // Frequency number overlay (LFU, if slots wide enough)
            if (isLFU && sw > 18 && i < keys.size()) {
                Map<String, Object> info = cache.getEntryInfo(keys.get(i));
                int freq = (int) info.getOrDefault("frequency", 1);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
                g2.drawString(String.valueOf(freq), x + 2, y + sh - 2);
            }
        }

        // MRU / LRU direction label
        if (!isLFU && !keys.isEmpty()) {
            int rowPx = topY + rows * (sh + 2) + 14;
            g2.setFont(theme.smallFont());
            g2.setColor(SLOT_MRU);          g2.drawString("MRU", pad, rowPx);
            g2.setColor(theme.dimFontColor()); g2.drawString("LRU", pad + cols * (sw + 2) - 20, rowPx);
        }
    }

    // ---- ARC bar chart ---------------------------------------------------

    private void drawARC(Graphics2D g2, int w, int h, ARCCache arc) {
        Theme theme = Theme.get();
        int t1 = arc.getT1Size(), t2 = arc.getT2Size();
        int b1 = arc.getB1Size(), b2 = arc.getB2Size();
        int p  = arc.getP(), cap = arc.capacity();

        g2.setFont(theme.headerFont());
        g2.setColor(theme.fontColor());
        g2.drawString(String.format("ARC Cache  —  p=%d  T1=%d  T2=%d  B1=%d  B2=%d", p, t1, t2, b1, b2), 20, 22);

        int pad = 20, maxW = w - 2*pad, bh = 38;
        arcBar(g2, pad, 50,  maxW, bh, "T1  recent × 1",  t1, cap, ARC_T1,    true);
        arcBar(g2, pad, 100, maxW, bh, "T2  frequent",     t2, cap, ARC_T2,    true);
        arcBar(g2, pad, 150, maxW, bh, "B1  ghost of T1",  b1, cap, ARC_GHOST, false);
        arcBar(g2, pad, 200, maxW, bh, "B2  ghost of T2",  b2, cap, ARC_GHOST, false);

        // Adaptive-p marker across T1 bar
        int pX = pad + (cap > 0 ? (int)((double) p / cap * maxW) : 0);
        g2.setColor(CLK_HAND);
        float[] dash = {5f, 3f};
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f));
        g2.drawLine(pX, 42, pX, 97);
        g2.setStroke(new BasicStroke(1f));
        g2.setFont(theme.smallFont());
        g2.setColor(CLK_HAND);
        g2.drawString("p=" + p, pX + 3, 42);

        g2.setFont(theme.smallFont());
        g2.setColor(theme.dimFontColor());
        g2.drawString("T1/T2 hold live data.  B1/B2 are ghost entries (metadata only, no bytes).", pad, 260);
        g2.drawString("B1 hit  →  p grows (favour recency).  B2 hit  →  p shrinks (favour frequency).", pad, 278);
    }

    private void arcBar(Graphics2D g2, int x, int y, int maxW, int h,
                        String label, int val, int cap, Color color, boolean solid) {
        int barW = cap > 0 ? (int)((double) val / cap * maxW) : 0;
        g2.setColor(SLOT_EMPTY); g2.fillRoundRect(x, y, maxW, h, 6, 6);
        if (barW > 0) {
            g2.setColor(solid ? color : ARC_GHOST);
            g2.fillRoundRect(x, y, barW, h, 6, 6);
        }
        if (!solid) {
            float[] dash = {4f, 3f};
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f));
            g2.setColor(color.brighter()); g2.drawRoundRect(x, y, maxW, h, 6, 6);
            g2.setStroke(new BasicStroke(1f));
        }
        g2.setFont(Theme.get().headerFont());
        g2.setColor(solid ? Color.WHITE : new Color(140,140,180));
        g2.drawString(label + "  [" + val + "]", x + 10, y + h / 2 + 4);
    }

    // ---- Clock face -------------------------------------------------------

    private void drawClock(Graphics2D g2, int w, int h, ClockCache clock) {
        Theme theme = Theme.get();
        String[] keys   = clock.getKeysCopy();
        boolean[] refs  = clock.getRefBitsCopy();
        int hand        = clock.getHandPosition();
        int cap         = clock.capacity();
        int size        = clock.size();

        g2.setFont(theme.headerFont());
        g2.setColor(theme.fontColor());
        g2.drawString(String.format("CLOCK Cache  —  %d / %d  |  hand → slot %d", size, cap, hand), 20, 22);

        // Legend
        legend(g2, 20, 42, CLK_REF,   "ref-bit = 1  (second chance)");
        legend(g2, 230, 42, SLOT_FILL, "ref-bit = 0");
        legend(g2, 360, 42, CLK_HAND,  "clock hand");
        legend(g2, 460, 42, SLOT_EMPTY,"empty");

        if (cap == 0) return;
        int cx = w / 2, cy = h / 2 + 22;
        int radius = (int)(Math.min(w, h - 80) * 0.42);
        int dotR   = Math.max(6, Math.min(18, (int)(Math.PI * radius / cap) - 2));

        for (int i = 0; i < cap; i++) {
            double a  = 2 * Math.PI * i / cap - Math.PI / 2;
            int sx    = (int)(cx + radius * Math.cos(a));
            int sy    = (int)(cy + radius * Math.sin(a));

            Color c;
            if      (keys[i] == null) c = SLOT_EMPTY;
            else if (i == hand)       c = CLK_HAND;
            else if (refs[i])         c = CLK_REF;
            else                      c = SLOT_FILL;

            g2.setColor(c);
            g2.fillOval(sx - dotR, sy - dotR, dotR * 2, dotR * 2);

            // Ring around the hand slot
            if (i == hand) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(sx - dotR - 3, sy - dotR - 3, (dotR+3)*2, (dotR+3)*2);
                // Hand line from centre
                g2.setColor(CLK_HAND);
                g2.drawLine(cx, cy, sx, sy);
                g2.setStroke(new BasicStroke(1f));
            }
        }
        // Hub
        g2.setColor(theme.headerBackground());
        g2.fillOval(cx - 10, cy - 10, 20, 20);
        g2.setColor(theme.dimFontColor());
        g2.setFont(new Font("SansSerif", Font.BOLD, 8));
        g2.drawString("CLK", cx - 9, cy + 3);
    }

    // ---- Helpers ----------------------------------------------------------

    private static CachePolicy unwrap(CachePolicy p) {
        return (p instanceof PredictiveCache pc) ? pc.getBaseCache() : p;
    }

    private static void legend(Graphics2D g2, int x, int y, Color color, String label) {
        g2.setColor(color); g2.fillRoundRect(x, y - 9, 14, 10, 3, 3);
        g2.setColor(Theme.get().fontColor()); g2.setFont(Theme.get().smallFont());
        g2.drawString(label, x + 18, y);
    }

    private static void placeholder(Graphics2D g2, int w, int h, String msg) {
        g2.setColor(Theme.get().dimFontColor());
        g2.setFont(Theme.get().bodyFont());
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
    }

    private static Color blend(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int)(a.getRed()   + (b.getRed()   - a.getRed())   * t),
                         (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                         (int)(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
    }

    private static JLabel dimLabel(String txt) {
        JLabel l = new JLabel(txt);
        l.setForeground(Theme.get().dimFontColorUI());
        l.setFont(Theme.get().smallFontUI());
        return l;
    }
}
