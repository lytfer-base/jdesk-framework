package com.jdesk.frame.laf;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * Reads the JDesk semantic color tokens that the theme manager writes into the {@link UIManager} defaults from a
 * {@link JdfThemePalette}. A missing token yields a built-in fallback, so components can paint before a theme is
 * installed.
 */
public final class JdfColors {

    private JdfColors() {
    }

    public static Color get(String token, Color fallback) {
        Color color = UIManager.getColor(token);
        return color == null ? fallback : color;
    }

    public static boolean isDark() {
        Object value = UIManager.get(JdfThemePalette.DARK);
        return Boolean.TRUE.equals(value);
    }

    public static Color background() {
        return get(JdfThemePalette.BACKGROUND, new Color(251, 252, 252));
    }

    public static Color panelBackground() {
        return get(JdfThemePalette.PANEL_BACKGROUND, Color.WHITE);
    }

    public static Color cardBackground() {
        return get(JdfThemePalette.CARD_BACKGROUND, Color.WHITE);
    }

    public static Color surface() {
        return get(JdfThemePalette.SURFACE, new Color(255, 255, 255));
    }

    public static Color surfaceAlt() {
        return get(JdfThemePalette.SURFACE_ALT, new Color(241, 244, 247));
    }

    public static Color controlBackground() {
        return get(JdfThemePalette.CONTROL_BACKGROUND, Color.WHITE);
    }

    public static Color scrollbarThumb() {
        return get(JdfThemePalette.SCROLLBAR_THUMB, new Color(201, 204, 214));
    }

    public static Color hoverBackground() {
        return get(JdfThemePalette.HOVER_BACKGROUND, new Color(234, 246, 249));
    }

    public static Color pressedBackground() {
        return get(JdfThemePalette.PRESSED_BACKGROUND, new Color(211, 237, 243));
    }

    public static Color activeBackground() {
        return get(JdfThemePalette.ACTIVE_BACKGROUND, new Color(226, 247, 251));
    }

    public static Color accent() {
        return get(JdfThemePalette.ACCENT, new Color(8, 153, 182));
    }

    public static Color accentForeground() {
        return get(JdfThemePalette.ACCENT_FOREGROUND, Color.WHITE);
    }

    public static Color border() {
        return get(JdfThemePalette.BORDER, new Color(222, 229, 233));
    }

    public static Color focus() {
        return get(JdfThemePalette.FOCUS, new Color(8, 153, 182));
    }

    public static Color text() {
        return get(JdfThemePalette.TEXT, new Color(16, 36, 46));
    }

    public static Color mutedText() {
        return get(JdfThemePalette.MUTED_TEXT, new Color(96, 115, 125));
    }

    /** Readable secondary text color; falls back to {@link #disabledText()} for themes that don't define it. */
    public static Color weakText() {
        return get(JdfThemePalette.WEAK_TEXT, disabledText());
    }

    public static Color selectionBackground() {
        return get(JdfThemePalette.SELECTION_BACKGROUND, new Color(226, 247, 251));
    }

    public static Color selectionForeground() {
        return get(JdfThemePalette.SELECTION_FOREGROUND, new Color(8, 67, 82));
    }

    public static Color disabledText() {
        return get(JdfThemePalette.DISABLED_TEXT, new Color(148, 160, 166));
    }

    public static Color success() {
        return get(JdfThemePalette.SUCCESS, new Color(36, 183, 120));
    }

    public static Color warning() {
        return get(JdfThemePalette.WARNING, new Color(201, 135, 24));
    }

    public static Color danger() {
        return get(JdfThemePalette.DANGER, new Color(219, 77, 92));
    }
}
