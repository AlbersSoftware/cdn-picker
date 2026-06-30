package com.cdnworkbench.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom-painted real-time time-series chart.
 * Supports multiple named series; auto-scales the Y axis.
 * All data access is on the EDT (driven by a Swing Timer), so no
 * extra synchronisation is needed inside this class.
 *
 * Background/grid-line/text colors and font sizes are read fresh from
 * Theme.get() on every paint, and the constructor registers a
 * Theme.get().addListener(this::repaint) so changes apply live — this
 * panel paints everything directly with Graphics2D, bypassing Swing's
 * background/foreground properties entirely, so it can't rely on the
 * UIResource auto-refresh mechanism other components use.
 */
public final class TimeSeriesChart extends JPanel {

    // Series line colors stay fixed — they're how you tell one line from
    // another, not a stylistic background/foreground choice.
    private static final Color[] SERIES_COLORS = {
        new Color(88,  166, 255),   // blue
        new Color(63,  185,  80),   // green
        new Color(255, 166,  77),   // amber
        new Color(255,  85,  85),   // red
        new Color(187, 128, 255),   // purple
    };

    private final String title;
    private final String unit;
    private double fixedYMax = 0;      // 0 → auto-scale
    private final List<Series> series = new ArrayList<>();

    // ---- Inner series type ------------------------------------------------

    public static final class Series {
        public  final String name;
        public  final Color  color;
        private final int    maxPoints;
        private final List<Double> data = new ArrayList<>();

        Series(String name, Color color, int maxPoints) {
            this.name = name; this.color = color; this.maxPoints = maxPoints;
        }
        public void add(double v) {
            data.add(v);
            if (data.size() > maxPoints) data.remove(0);
        }
        public void clear() { data.clear(); }
        List<Double> snapshot() { return new ArrayList<>(data); }
    }

    // ---- Construction -----------------------------------------------------

    public TimeSeriesChart(String title, String unit) {
        this.title = title; this.unit = unit;
        setBackground(Theme.get().backgroundUI());
        setPreferredSize(new Dimension(320, 200));
        Theme.get().addListener(this::repaint);
    }

    public Series addSeries(String name) {
        Series s = new Series(name, SERIES_COLORS[series.size() % SERIES_COLORS.length], 120);
        series.add(s);
        return s;
    }

    /** Fix the Y-axis maximum (disables auto-scale). */
    public void setYMax(double max) { fixedYMax = max; }

    /** Convenience: push a value to the first (or only) series. */
    public void push(double v) { if (!series.isEmpty()) { series.get(0).add(v); repaint(); } }

    /** Push a value to a specific series by index. */
    public void push(int idx, double v) {
        if (idx < series.size()) { series.get(idx).add(v); repaint(); }
    }

    public void clearAll() { series.forEach(Series::clear); repaint(); }

    // ---- Painting ---------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Theme theme = Theme.get();
        Color bg     = theme.background();
        Color grid   = theme.separator();
        Color text   = theme.fontColor();
        Color textDim= theme.dimFontColor();

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        int pL = 52, pR = 12, pT = 28, pB = 30;
        int cW = w - pL - pR, cH = h - pT - pB;

        g2.setColor(bg); g2.fillRect(0, 0, w, h);

        // Title
        g2.setFont(theme.headerFont());
        g2.setColor(text);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(title, (w - fm.stringWidth(title)) / 2, 18);

        // Determine Y scale
        double yMax = fixedYMax > 0 ? fixedYMax : computeAutoMax();
        if (yMax < 0.001) yMax = 1.0;

        // Grid + Y-axis labels (4 divisions)
        g2.setFont(theme.smallFont());
        g2.setColor(textDim); fm = g2.getFontMetrics();
        for (int i = 0; i <= 4; i++) {
            int y = pT + cH - i * cH / 4;
            g2.setColor(grid);
            g2.setStroke(new BasicStroke(0.5f));
            g2.drawLine(pL, y, pL + cW, y);
            String label = formatVal(yMax * i / 4);
            g2.setColor(textDim);
            g2.drawString(label, pL - fm.stringWidth(label) - 4, y + 4);
        }

        // Chart border
        g2.setColor(grid); g2.setStroke(new BasicStroke(1f));
        g2.drawRect(pL, pT, cW, cH);

        // Series lines + filled area
        for (Series s : series) {
            List<Double> pts = s.snapshot();
            if (pts.size() < 2) continue;
            int n = pts.size();

            Path2D line = new Path2D.Float();
            boolean first = true;
            for (int i = 0; i < n; i++) {
                double x = pL + (double) i / (n - 1) * cW;
                double y = pT + cH - clamp(pts.get(i) / yMax, 0, 1) * cH;
                if (first) { line.moveTo(x, y); first = false; } else line.lineTo(x, y);
            }

            // Semi-transparent fill
            Path2D fill = new Path2D.Float(line);
            fill.lineTo(pL + cW, pT + cH); fill.lineTo(pL, pT + cH); fill.closePath();
            g2.setColor(new Color(s.color.getRed(), s.color.getGreen(), s.color.getBlue(), 35));
            g2.fill(fill);

            // Line
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(s.color);
            g2.draw(line);
        }
        g2.setStroke(new BasicStroke(1f));

        // Legend (top-left inside chart area)
        if (!series.isEmpty()) {
            int lx = pL + 6, ly = pT + 14;
            g2.setFont(theme.smallFont());
            fm = g2.getFontMetrics();
            for (Series s : series) {
                g2.setColor(s.color);
                g2.fillRect(lx, ly - 7, 14, 3);
                g2.setColor(text);
                g2.drawString(s.name, lx + 18, ly);
                lx += fm.stringWidth(s.name) + 34;
            }
        }

        // Y-axis unit label (rotated)
        g2.setFont(theme.smallFont());
        g2.setColor(textDim);
        AffineTransform orig = g2.getTransform();
        g2.rotate(-Math.PI / 2, 11, h / 2.0);
        g2.drawString(unit, 11 - g2.getFontMetrics().stringWidth(unit) / 2, h / 2);
        g2.setTransform(orig);

        g2.dispose();
    }

    private double computeAutoMax() {
        double max = 0.001;
        for (Series s : series)
            for (double v : s.snapshot()) if (v > max) max = v;
        return max * 1.25;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static String formatVal(double v) {
        if (v >= 10_000) return String.format("%.0fK", v / 1000);
        if (v >= 1_000)  return String.format("%.1fK", v / 1000);
        if (v >= 10)     return String.format("%.0f",  v);
        if (v >= 1)      return String.format("%.1f",  v);
        return String.format("%.2f", v);
    }
}
