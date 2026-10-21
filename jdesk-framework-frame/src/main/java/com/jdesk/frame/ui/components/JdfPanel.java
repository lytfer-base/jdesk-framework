package com.jdesk.frame.ui.components;

import com.jdesk.frame.laf.JdfColors;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.LayoutManager;

/**
 * Basic themed panel for frames and application shells. It names a semantic {@link Surface} instead of a color, so a
 * theme switch only has to read the color again from {@link com.jdesk.frame.laf.JdfColors}.
 */
public class JdfPanel extends JPanel implements JdfThemedComponent {

    /** Semantic surface of a panel, mapped to a background color of the current theme. */
    public enum Surface {
        BACKGROUND,
        PANEL,
        SURFACE,
        SURFACE_ALT,
        CARD,
        CONTROL,
        TRANSPARENT
    }

    /** Can change at runtime, so that the same panel can be reused. */
    private Surface surface;

    public JdfPanel() {
        this(new BorderLayout(), Surface.PANEL);
    }

    public JdfPanel(LayoutManager layout) {
        this(layout, Surface.PANEL);
    }

    public JdfPanel(LayoutManager layout, Surface surface) {
        super(layout);
        this.surface = surface == null ? Surface.PANEL : surface;
        applySurfaceTheme();
    }

    public Surface getSurface() {
        return surface;
    }

    public void setSurface(Surface surface) {
        this.surface = surface == null ? Surface.PANEL : surface;
        updateJdfTheme();
    }

    @Override
    public void updateJdfTheme() {
        applySurfaceTheme();
    }

    private void applySurfaceTheme() {
        if (surface == Surface.TRANSPARENT) {
            setOpaque(false);
            setForeground(JdfColors.text());
            return;
        }
        setOpaque(true);
        setBackground(colorFor(surface));
        setForeground(JdfColors.text());
    }

    private static Color colorFor(Surface surface) {
        return switch (surface) {
            case BACKGROUND -> JdfColors.background();
            case SURFACE -> JdfColors.surface();
            case SURFACE_ALT -> JdfColors.surfaceAlt();
            case CARD -> JdfColors.cardBackground();
            case CONTROL -> JdfColors.controlBackground();
            default -> JdfColors.panelBackground();
        };
    }
}
