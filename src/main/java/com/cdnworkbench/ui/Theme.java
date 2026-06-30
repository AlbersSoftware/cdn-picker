package com.cdnworkbench.ui;

import javax.swing.SwingUtilities;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralized, live-editable visual theme: background, separator/line
 * color, font color, and base font size. Configurable via Settings ->
 * Theme (see ThemeSettingsDialog).
 *
 * Two refresh mechanisms work together:
 *
 *  1. Plain backgrounds/foregrounds/fonts are handed out wrapped in
 *     ColorUIResource/FontUIResource (the *UI() getters below). Swing's
 *     look-and-feel machinery treats UIResource-wrapped values as "still
 *     using the default, not explicitly customized" — so a single
 *     SwingUtilities.updateComponentTreeUI(frame) call (done by
 *     MainFrame's theme listener) cascades new values into every
 *     component that used these wrappers, automatically, with no manual
 *     per-component bookkeeping required.
 *
 *  2. Borders (TitledBorder/MatteBorder/LineBorder) are immutable value
 *     objects with no such refresh mechanism. Anywhere a border bakes in
 *     the separator color, that call site registers its own small
 *     listener (via addListener) to rebuild just that one border when the
 *     theme changes.
 *
 *  3. Fully custom-painted panels (the cache visualizer, network graph,
 *     playback canvas, charts) read Theme.get() fresh at the top of their
 *     paintComponent() and register a `Theme.get().addListener(this::repaint)`
 *     in their constructor, since they bypass Swing's background/foreground
 *     properties entirely.
 *
 * Semantic/informational colors (cache hit=green, miss=orange, network
 * load red/amber/green, ARC/Clock visualization colors, button intents
 * like Start=green/Stop=red, etc.) are intentionally NOT themeable here —
 * they carry meaning, not just style, and making them user-editable would
 * make the dashboards harder to read consistently.
 */
public final class Theme {

    private static final Theme INSTANCE = new Theme();
    public static Theme get() { return INSTANCE; }

    // Defaults match the original dark theme
    private Color background = new Color(15, 15, 24);
    private Color separator  = new Color(45, 45, 70);
    private Color fontColor  = new Color(175, 175, 205);
    private int   fontSize   = 12;

    private final List<Runnable> listeners = new ArrayList<>();

    private Theme() { }

    // ── Plain getters ────────────────────────────────────────────────────

    public Color background() { return background; }
    public Color separator()  { return separator; }
    public Color fontColor()  { return fontColor; }
    public int   fontSize()   { return fontSize; }

    /** Lighter than background — input fields / inner content panels. */
    public Color panelBackground() { return lighten(background, 16); }

    /** Darker than background — toolbars / headers / status bars. */
    public Color headerBackground() { return darken(background, 7); }

    /** Faded font color — secondary/dim text. */
    public Color dimFontColor() { return blend(fontColor, background, 0.45f); }

    // ── UIResource-wrapped getters (auto-refresh via updateComponentTreeUI) ─

    public Color backgroundUI()       { return new ColorUIResource(background); }
    public Color panelBackgroundUI()  { return new ColorUIResource(panelBackground()); }
    public Color headerBackgroundUI() { return new ColorUIResource(headerBackground()); }
    public Color fontColorUI()        { return new ColorUIResource(fontColor); }
    public Color dimFontColorUI()     { return new ColorUIResource(dimFontColor()); }

    public Font titleFont()   { return new Font("SansSerif", Font.BOLD,  fontSize + 3); }
    public Font headerFont()  { return new Font("SansSerif", Font.BOLD,  fontSize);     }
    public Font bodyFont()    { return new Font("SansSerif", Font.PLAIN, fontSize);     }
    public Font smallFont()   { return new Font("SansSerif", Font.PLAIN, Math.max(8, fontSize - 2)); }
    public Font monoFont()    { return new Font("Monospaced", Font.PLAIN, fontSize);    }
    public Font monoBoldFont(){ return new Font("Monospaced", Font.BOLD, fontSize);     }

    public Font titleFontUI()   { return new FontUIResource(titleFont()); }
    public Font headerFontUI()  { return new FontUIResource(headerFont()); }
    public Font bodyFontUI()    { return new FontUIResource(bodyFont()); }
    public Font smallFontUI()   { return new FontUIResource(smallFont()); }
    public Font monoFontUI()    { return new FontUIResource(monoFont()); }
    public Font monoBoldFontUI(){ return new FontUIResource(monoBoldFont()); }

    // ── Setters (notify listeners; the caller — MainFrame — is responsible
    //    for following up with SwingUtilities.updateComponentTreeUI) ───────

    public void setBackground(Color c) { background = c; fireChanged(); }
    public void setSeparator(Color c)  { separator  = c; fireChanged(); }
    public void setFontColor(Color c)  { fontColor  = c; fireChanged(); }
    public void setFontSize(int size)  { fontSize = Math.max(8, Math.min(28, size)); fireChanged(); }

    public void resetToDefaults() {
        background = new Color(15, 15, 24);
        separator  = new Color(45, 45, 70);
        fontColor  = new Color(175, 175, 205);
        fontSize   = 12;
        fireChanged();
    }

    // ── Change notification ─────────────────────────────────────────────

    /** Register a listener invoked (on the EDT) whenever the theme changes. */
    public void addListener(Runnable r) { listeners.add(r); }

    private void fireChanged() {
        List<Runnable> snapshot = new ArrayList<>(listeners);
        Runnable fireAll = () -> snapshot.forEach(Runnable::run);
        if (SwingUtilities.isEventDispatchThread()) fireAll.run();
        else SwingUtilities.invokeLater(fireAll);
    }

    // ── Color math ───────────────────────────────────────────────────────

    private static Color lighten(Color c, int amt) {
        return new Color(clamp(c.getRed()+amt), clamp(c.getGreen()+amt), clamp(c.getBlue()+amt));
    }
    private static Color darken(Color c, int amt) {
        return new Color(clamp(c.getRed()-amt), clamp(c.getGreen()-amt), clamp(c.getBlue()-amt));
    }
    private static Color blend(Color a, Color b, float t) {
        return new Color(
            (int)(a.getRed()   + (b.getRed()   - a.getRed())   * t),
            (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int)(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
    }
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
