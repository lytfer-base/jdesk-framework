package com.jdesk.frame.laf;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable palette of semantic colors for a frame theme, keyed by UI defaults key: backgrounds, panels, controls,
 * text, selection and status colors. Building it checks that every required token is present, so components never
 * read a null color after a theme switch.
 */
public final class JdfThemePalette {

    public static final String BACKGROUND = "Jdf.background";
    public static final String PANEL_BACKGROUND = "Jdf.panelBackground";
    public static final String CARD_BACKGROUND = "Jdf.cardBackground";
    public static final String SURFACE = "Jdf.surface";
    public static final String SURFACE_ALT = "Jdf.surfaceAlt";
    public static final String CONTROL_BACKGROUND = "Jdf.controlBackground";
    public static final String SCROLLBAR_THUMB = "Jdf.scrollbarThumb";
    public static final String HOVER_BACKGROUND = "Jdf.hoverBackground";
    public static final String PRESSED_BACKGROUND = "Jdf.pressedBackground";
    public static final String ACTIVE_BACKGROUND = "Jdf.activeBackground";
    public static final String ACCENT = "Jdf.accent";
    public static final String ACCENT_FOREGROUND = "Jdf.accentForeground";
    public static final String BORDER = "Jdf.border";
    public static final String FOCUS = "Jdf.focus";
    public static final String TEXT = "Jdf.text";
    public static final String MUTED_TEXT = "Jdf.mutedText";
    public static final String WEAK_TEXT = "Jdf.weakText";
    public static final String SELECTION_BACKGROUND = "Jdf.selectionBackground";
    public static final String SELECTION_FOREGROUND = "Jdf.selectionForeground";
    public static final String DISABLED_TEXT = "Jdf.disabledText";
    public static final String SUCCESS = "Jdf.success";
    public static final String WARNING = "Jdf.warning";
    public static final String DANGER = "Jdf.danger";

    static final String DARK = "Jdf.dark";

    /** Tokens every theme must provide. */
    private static final List<String> REQUIRED_TOKENS = List.of(
            BACKGROUND,
            PANEL_BACKGROUND,
            CARD_BACKGROUND,
            SURFACE,
            SURFACE_ALT,
            CONTROL_BACKGROUND,
            SCROLLBAR_THUMB,
            HOVER_BACKGROUND,
            PRESSED_BACKGROUND,
            ACTIVE_BACKGROUND,
            ACCENT,
            ACCENT_FOREGROUND,
            BORDER,
            FOCUS,
            TEXT,
            MUTED_TEXT,
            SELECTION_BACKGROUND,
            SELECTION_FOREGROUND,
            DISABLED_TEXT,
            SUCCESS,
            WARNING,
            DANGER
    );

    private final Map<String, Color> colors;

    private JdfThemePalette(Map<String, Color> colors) {
        this.colors = Map.copyOf(colors);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Color getColor(String token) {
        return colors.get(token);
    }

    public Color getColor(String token, Color fallback) {
        Color color = getColor(token);
        return color == null ? fallback : color;
    }

    public Map<String, Color> asMap() {
        return colors;
    }

    /** Palette builder; each shorthand method sets its own token. */
    public static final class Builder {

        private final Map<String, Color> colors = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder color(String token, Color color) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("token cannot be blank");
            }
            colors.put(token, Objects.requireNonNull(color, "color"));
            return this;
        }

        public Builder background(Color color) {
            return color(BACKGROUND, color);
        }

        public Builder panelBackground(Color color) {
            return color(PANEL_BACKGROUND, color);
        }

        public Builder cardBackground(Color color) {
            return color(CARD_BACKGROUND, color);
        }

        public Builder controlBackground(Color color) {
            return color(CONTROL_BACKGROUND, color);
        }

        public Builder scrollbarThumb(Color color) {
            return color(SCROLLBAR_THUMB, color);
        }

        public Builder surface(Color color) {
            return color(SURFACE, color);
        }

        public Builder surfaceAlt(Color color) {
            return color(SURFACE_ALT, color);
        }

        public Builder hoverBackground(Color color) {
            return color(HOVER_BACKGROUND, color);
        }

        public Builder pressedBackground(Color color) {
            return color(PRESSED_BACKGROUND, color);
        }

        public Builder activeBackground(Color color) {
            return color(ACTIVE_BACKGROUND, color);
        }

        public Builder accent(Color color) {
            return color(ACCENT, color);
        }

        public Builder accentForeground(Color color) {
            return color(ACCENT_FOREGROUND, color);
        }

        public Builder border(Color color) {
            return color(BORDER, color);
        }

        public Builder focus(Color color) {
            return color(FOCUS, color);
        }

        public Builder text(Color color) {
            return color(TEXT, color);
        }

        public Builder mutedText(Color color) {
            return color(MUTED_TEXT, color);
        }

        public Builder weakText(Color color) {
            return color(WEAK_TEXT, color);
        }

        public Builder selectionBackground(Color color) {
            return color(SELECTION_BACKGROUND, color);
        }

        public Builder selectionForeground(Color color) {
            return color(SELECTION_FOREGROUND, color);
        }

        public Builder disabledText(Color color) {
            return color(DISABLED_TEXT, color);
        }

        public Builder success(Color color) {
            return color(SUCCESS, color);
        }

        public Builder warning(Color color) {
            return color(WARNING, color);
        }

        public Builder danger(Color color) {
            return color(DANGER, color);
        }

        public JdfThemePalette build() {
            for (String token : REQUIRED_TOKENS) {
                if (!colors.containsKey(token)) {
                    throw new IllegalStateException("Missing theme color token: " + token);
                }
            }
            return new JdfThemePalette(new LinkedHashMap<>(colors));
        }
    }
}
