package com.jdesk.frame.laf;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A named JDesk theme: a stable ID, a display name read from its supplier on every call, a dark flag and a palette
 * of semantic color tokens. Components don't use theme objects directly; they read the colors that
 * {@link JdfLafManager} writes into {@link javax.swing.UIManager}.
 */
public final class JdfTheme {

    private final String id;
    private final Supplier<String> displayName;
    private final boolean dark;
    private final JdfThemePalette palette;

    private JdfTheme(String id, Supplier<String> displayName, boolean dark, JdfThemePalette palette) {
        this.id = requireText(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.dark = dark;
        this.palette = Objects.requireNonNull(palette, "palette");
    }

    public static JdfTheme of(String id, String displayName, boolean dark, JdfThemePalette palette) {
        String name = requireText(displayName, "displayName");
        return new JdfTheme(id, () -> name, dark, palette);
    }

    /** Use this when the display name follows the UI language, as in {@code () -> I18n.message(key)}. */
    public static JdfTheme of(String id, Supplier<String> displayName, boolean dark, JdfThemePalette palette) {
        return new JdfTheme(id, displayName, dark, palette);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName.get();
    }

    public boolean isDark() {
        return dark;
    }

    public JdfThemePalette getPalette() {
        return palette;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
