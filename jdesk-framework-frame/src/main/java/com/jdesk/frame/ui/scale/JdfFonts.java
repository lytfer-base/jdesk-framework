package com.jdesk.frame.ui.scale;

import com.jdesk.core.i18n.UiLanguage;
import com.jdesk.core.startup.StartupTrace;

import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.StyleContext;
import java.awt.Font;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Font helpers following the JDesk UI scale. Installing the defaults reuses the system font where possible and puts
 * scaled fonts under the common Swing font keys, so components using the standard keys follow the scale too.
 */
public final class JdfFonts {

    private static final char CJK_PROBE = '中';

    /** Swing font keys that {@link #installDefaults()} writes. */
    private static final String[] UI_FONT_KEYS = {
            "Button.font",
            "CheckBox.font",
            "ComboBox.font",
            "EditorPane.font",
            "FormattedTextField.font",
            "Label.font",
            "List.font",
            "Menu.font",
            "MenuBar.font",
            "MenuItem.font",
            "OptionPane.font",
            "Panel.font",
            "PasswordField.font",
            "PopupMenu.font",
            "RadioButton.font",
            "Spinner.font",
            "TabbedPane.font",
            "Table.font",
            "TableHeader.font",
            "TextArea.font",
            "TextField.font",
            "TextPane.font",
            "TitledBorder.font",
            "ToggleButton.font",
            "ToolBar.font",
            "ToolTip.font",
            "Tree.font"
    };

    /** Unscaled base fonts, so that installing again doesn't scale already scaled fonts. */
    private static final Map<String, Font> BASE_FONTS = new HashMap<>();

    private static volatile UiLanguage language = UiLanguage.ZH_CN;

    private JdfFonts() {
    }

    /** Installs the default fonts, updating the Swing defaults from the system font and the user scale. */
    public static synchronized void installDefaults() {
        installDefaults(StartupTrace.disabled());
    }

    /** Like {@link #installDefaults()}, recording the steps in the startup trace. */
    public static synchronized void installDefaults(StartupTrace startupTrace) {
        startupTrace.runSpan("frame.fonts.ensureScale", JdfScale::installEarly);
        UIDefaults[] defaultsHolder = new UIDefaults[1];
        startupTrace.runSpan("frame.fonts.readDefaults", () -> defaultsHolder[0] = UIManager.getDefaults());
        UIDefaults defaults = defaultsHolder[0];

        Font systemFont = detectSystemFont(startupTrace);

        startupTrace.runSpan("frame.fonts.updateScale", () -> JdfScale.updateFromSystemFont(systemFont));
        boolean preferSystemFont = shouldPreferSystemFont(systemFont);

        startupTrace.runSpan("frame.fonts.installUiDefaults", () -> {
            for (String key : UI_FONT_KEYS) {
                Font base = BASE_FONTS.computeIfAbsent(key, fontKey -> {
                    if (preferSystemFont) {
                        return systemFont;
                    }
                    Font existing = defaults.getFont(fontKey);
                    return existing == null ? systemFont : existing;
                });
                defaults.put(key, asUiResource(deriveDefaultFont(base)));
            }
        });

        startupTrace.runSpan("frame.fonts.installMonoDefaults", () -> {
            Font uiFont = defaults.getFont("Label.font");
            Font monoFont = new Font(Font.MONOSPACED, Font.PLAIN,
                    Math.max(1, Math.round(uiFont == null ? systemFont.getSize2D() : uiFont.getSize2D())));
            defaults.put("TextArea.font", asUiResource(monoFont));
            defaults.put("PasswordField.font", asUiResource(monoFont));
        });
    }

    /** Sets the UI language, whose font family the next {@link #installDefaults()} picks. */
    public static synchronized void setLanguage(UiLanguage newLanguage) {
        language = Objects.requireNonNull(newLanguage, "newLanguage");
        // Don't clear it off Windows: the bases come from current UIManager values and would get scaled twice.
        if (isWindows()) {
            BASE_FONTS.clear();
        }
    }

    /** Returns the label font, or a scaled Dialog font if UIManager has none. */
    public static Font label() {
        Font font = UIManager.getFont("Label.font");
        return font != null ? font : new Font(Font.DIALOG, Font.PLAIN, JdfScale.scaleFontSize(12f));
    }

    /** Returns the label font, plain, at the given size. */
    public static Font label(float size) {
        return derive(label(), Font.PLAIN, size);
    }

    /** Returns the small UI font. */
    public static Font small() {
        return label(11f);
    }

    /** Returns the smaller font for secondary text. */
    public static Font mini() {
        return label(10f);
    }

    /** Returns a monospaced font at the given size. */
    public static Font mono(float size) {
        return new Font(Font.MONOSPACED, Font.PLAIN, JdfScale.scaleFontSize(size));
    }

    /** Returns a monospaced font about the size of the label font. */
    public static Font mono() {
        Font label = label();
        return new Font(Font.MONOSPACED, Font.PLAIN, Math.max(1, Math.round(label.getSize2D())));
    }

    /** Derives a font with the given style and size from {@code base}, applying the JDesk font scale. */
    public static Font derive(Font base, int style, float size) {
        Font source = base != null ? base : label();
        return source.deriveFont(style, JdfScale.scaleFontSize(size));
    }

    /** Clears the base font cache and restores the default language; tests only. */
    public static void resetForTests() {
        synchronized (JdfFonts.class) {
            BASE_FONTS.clear();
            language = UiLanguage.ZH_CN;
        }
    }

    private static FontUIResource asUiResource(Font font) {
        return font instanceof FontUIResource fontUIResource
                ? fontUIResource
                : new FontUIResource(font);
    }

    private static Font deriveDefaultFont(Font base) {
        if (JdfScale.hasExplicitUserScale()) {
            return base.deriveFont(base.getStyle(), JdfScale.scaleFontSize(base.getSize2D()));
        }
        return base;
    }

    private static Font detectSystemFont(StartupTrace startupTrace) {
        try {
            return startupTrace.runSpan("frame.fonts.detectSystemFont", span -> {
                Font font = detectSystemFont();
                span.detail("source", "fastOsDefault");
                span.detail("name", font.getName());
                span.detail("size", font.getSize());
                return font;
            });
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Font detectSystemFont() {
        if (isWindows()) {
            Font preferred = installedFont(12, windowsFamilies(language));
            if (preferred != null) {
                return preferred;
            }
            return firstDisplayableFont(12,
                    "Microsoft YaHei UI",
                    "Microsoft JhengHei UI",
                    "Microsoft YaHei",
                    "SimSun",
                    Font.DIALOG,
                    "Segoe UI");
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        return new Font(Font.DIALOG, Font.PLAIN, 12);
    }

    static List<String> windowsFamilies(UiLanguage uiLanguage) {
        return switch (uiLanguage) {
            case JA -> List.of("Yu Gothic UI", "Meiryo UI", "Meiryo", "MS UI Gothic");
            case ZH_CN -> List.of("Microsoft YaHei UI", "Microsoft YaHei");
            case EN, PSEUDO -> List.of("Segoe UI");
        };
    }

    /** Returns the first installed family as a composite font with fallbacks (via StyleContext), or null if none is. */
    private static Font installedFont(int size, List<String> families) {
        for (String family : families) {
            Font font = StyleContext.getDefaultStyleContext().getFont(family, Font.PLAIN, size);
            if (font.getFamily(Locale.ROOT).equalsIgnoreCase(family)) {
                return font;
            }
        }
        return null;
    }

    private static Font firstDisplayableFont(int size, String... names) {
        for (String name : names) {
            Font font = new Font(name, Font.PLAIN, size);
            if (font.canDisplay(CJK_PROBE)) {
                return font;
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, size);
    }

    /** If you change this, check {@link #setLanguage}: it clears the base cache under the same condition. */
    private static boolean shouldPreferSystemFont(Font systemFont) {
        return systemFont != null && isWindows();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
