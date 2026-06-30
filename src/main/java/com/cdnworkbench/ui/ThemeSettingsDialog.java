package com.cdnworkbench.ui;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Settings -> Theme dialog: three color pickers (background, separator
 * lines, font color) plus a font-size spinner. Non-modal so the user can
 * see changes apply live to the main window behind it as they pick.
 */
public final class ThemeSettingsDialog extends JDialog {

    public ThemeSettingsDialog(Frame owner) {
        super(owner, "Theme Settings", false);
        Theme theme = Theme.get();

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        root.setBackground(theme.background());

        JLabel heading = new JLabel("Theme");
        heading.setForeground(theme.fontColor());
        heading.setFont(theme.titleFont());
        heading.setAlignmentX(LEFT_ALIGNMENT);
        root.add(heading);
        root.add(Box.createVerticalStrut(12));

        root.add(colorRow(theme, "Background",        theme::background, theme::setBackground));
        root.add(Box.createVerticalStrut(8));
        root.add(colorRow(theme, "Lines / Separators", theme::separator,  theme::setSeparator));
        root.add(Box.createVerticalStrut(8));
        root.add(colorRow(theme, "Font Color",         theme::fontColor,  theme::setFontColor));
        root.add(Box.createVerticalStrut(12));
        root.add(fontSizeRow(theme));
        root.add(Box.createVerticalStrut(16));

        JButton resetBtn = new JButton("Reset to Defaults");
        resetBtn.setFocusPainted(false);
        resetBtn.addActionListener(e -> theme.resetToDefaults());
        JButton closeBtn = new JButton("Close");
        closeBtn.setFocusPainted(false);
        closeBtn.addActionListener(e -> dispose());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.setAlignmentX(LEFT_ALIGNMENT);
        btnRow.add(resetBtn);
        btnRow.add(closeBtn);
        root.add(btnRow);

        setContentPane(root);
        setMinimumSize(new Dimension(360, 300));
        pack();
        setLocationRelativeTo(owner);

        // This dialog is a separate top-level window, outside MainFrame's
        // component tree, so it re-tints its own chrome independently.
        theme.addListener(() -> {
            root.setBackground(theme.background());
            heading.setForeground(theme.fontColor());
            heading.setFont(theme.titleFont());
            repaint();
        });
    }

    private JPanel colorRow(Theme theme, String label, Supplier<Color> current, Consumer<Color> onPick) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        JLabel l = new JLabel(label);
        l.setForeground(theme.fontColor());
        l.setFont(theme.bodyFont());
        row.add(l, BorderLayout.WEST);

        JButton swatch = new JButton();
        swatch.setPreferredSize(new Dimension(70, 26));
        swatch.setBackground(current.get());
        swatch.setBorder(BorderFactory.createLineBorder(theme.separator()));
        swatch.setFocusPainted(false);
        swatch.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(this, "Choose " + label, swatch.getBackground());
            if (chosen != null) {
                swatch.setBackground(chosen);
                onPick.accept(chosen);
            }
        });
        row.add(swatch, BorderLayout.EAST);

        // Keep label/swatch in sync if another control (or Reset) changes the theme
        theme.addListener(() -> {
            l.setForeground(theme.fontColor());
            l.setFont(theme.bodyFont());
            swatch.setBackground(current.get());
            swatch.setBorder(BorderFactory.createLineBorder(theme.separator()));
        });

        return row;
    }

    private JPanel fontSizeRow(Theme theme) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        JLabel l = new JLabel("Font Size");
        l.setForeground(theme.fontColor());
        l.setFont(theme.bodyFont());
        row.add(l, BorderLayout.WEST);

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(theme.fontSize(), 8, 28, 1));
        spinner.setPreferredSize(new Dimension(70, 26));
        spinner.addChangeListener(e -> theme.setFontSize((Integer) spinner.getValue()));
        row.add(spinner, BorderLayout.EAST);

        theme.addListener(() -> {
            l.setForeground(theme.fontColor());
            l.setFont(theme.bodyFont());
            if (!spinner.getValue().equals(theme.fontSize())) {
                spinner.setValue(theme.fontSize());
            }
        });

        return row;
    }
}
