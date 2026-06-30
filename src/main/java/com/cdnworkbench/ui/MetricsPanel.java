package com.cdnworkbench.ui;

import com.cdnworkbench.metrics.MetricsSnapshot;

import javax.swing.*;
import java.awt.*;

/**
 * Shows four real-time time-series charts and a headline stats bar.
 * Background/separator/font come from Theme; the accent blue used for
 * headline numbers stays fixed (semantic, not part of the theme request).
 */
public final class MetricsPanel extends JPanel {

    private static final Color ACCENT = new Color(88, 166, 255);

    // ---- Charts -----------------------------------------------------------
    private final TimeSeriesChart hitRateChart   = new TimeSeriesChart("Cache Hit Rate",    "% hits");
    private final TimeSeriesChart latencyChart   = new TimeSeriesChart("Avg Request Latency","ms");
    private final TimeSeriesChart bandwidthChart = new TimeSeriesChart("Total Bandwidth",   "Mbps");
    private final TimeSeriesChart originChart    = new TimeSeriesChart("Origin Traffic",    "Mbps");

    // ---- Headline labels --------------------------------------------------
    private final JLabel hitRateLbl   = accentLabel("--");
    private final JLabel latencyLbl   = accentLabel("--");
    private final JLabel bwLbl        = accentLabel("--");
    private final JLabel originLbl    = accentLabel("--");
    private final JLabel totalReqLbl  = accentLabel("--");
    private final JLabel rebufferLbl  = accentLabel("--");

    private final JPanel statsBar;
    private final JPanel grid;

    public MetricsPanel() {
        setLayout(new BorderLayout(0, 0));
        setBackground(Theme.get().backgroundUI());

        hitRateChart.addSeries("Hit Rate");
        hitRateChart.setYMax(100);

        latencyChart.addSeries("Avg ms");

        bandwidthChart.addSeries("Total");
        originChart.addSeries("Origin");

        statsBar = buildStatsBar();
        add(statsBar, BorderLayout.NORTH);

        grid = new JPanel(new GridLayout(2, 2, 2, 2));
        grid.setBackground(Theme.get().headerBackgroundUI());
        grid.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        grid.add(hitRateChart);
        grid.add(latencyChart);
        grid.add(bandwidthChart);
        grid.add(originChart);
        add(grid, BorderLayout.CENTER);
    }

    private JPanel buildStatsBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 24, 7));
        p.setBackground(Theme.get().headerBackgroundUI());
        Runnable applyBorder = () -> p.setBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.get().separator()));
        applyBorder.run();
        Theme.get().addListener(applyBorder);
        p.add(statBlock("Hit Rate",  hitRateLbl));
        p.add(statBlock("Avg Latency", latencyLbl));
        p.add(statBlock("Bandwidth", bwLbl));
        p.add(statBlock("Origin BW", originLbl));
        p.add(statBlock("Total Req", totalReqLbl));
        p.add(statBlock("Rebuffers", rebufferLbl));
        return p;
    }

    private static JPanel statBlock(String name, JLabel value) {
        JPanel p = new JPanel(new BorderLayout(0, 1));
        p.setBackground(Theme.get().headerBackgroundUI());
        JLabel n = new JLabel(name);
        n.setForeground(Theme.get().dimFontColorUI());
        n.setFont(Theme.get().smallFontUI());
        p.add(n, BorderLayout.NORTH);
        p.add(value, BorderLayout.CENTER);
        return p;
    }

    private static JLabel accentLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(ACCENT);
        l.setFont(Theme.get().monoBoldFontUI());
        return l;
    }

    // ---- Called every second from Swing Timer (EDT) ----------------------

    public void update(MetricsSnapshot s) {
        double hitPct = s.hitRate() * 100;
        hitRateChart.push(hitPct);
        latencyChart.push(s.avgLatencyMs());
        bandwidthChart.push(s.bandwidthMbps());
        originChart.push(s.originTrafficMbps());

        hitRateLbl.setText(String.format("%.1f%%",  hitPct));
        latencyLbl.setText(String.format("%.0f ms", s.avgLatencyMs()));
        bwLbl.setText     (String.format("%.2f Mbps", s.bandwidthMbps()));
        originLbl.setText (String.format("%.2f Mbps", s.originTrafficMbps()));
        totalReqLbl.setText(String.valueOf(s.totalRequests()));
        rebufferLbl.setText(String.valueOf(s.totalRebuffers()));
    }

    public void reset() {
        hitRateChart.clearAll(); latencyChart.clearAll();
        bandwidthChart.clearAll(); originChart.clearAll();
        hitRateLbl.setText("--"); latencyLbl.setText("--");
        bwLbl.setText("--"); originLbl.setText("--"); totalReqLbl.setText("--");
        rebufferLbl.setText("--");
    }
}
