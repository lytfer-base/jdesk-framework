package com.jdesk.core.i18n;

import com.jdesk.core.app.PathManager;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Decides the UI language at startup: {@code -Djdesk.locale} if set, otherwise the settings file negotiated against
 * the preferred system languages.
 */
public final class I18nStartup {
    public static final String LOCALE_PROPERTY = "jdesk.locale";
    public static final String OS_LANGUAGES_PROPERTY = "jdesk.os.languages";
    // This class must load before the first I18n.initialize, or this captures the UI language, not the system's.
    private static final Locale ORIGINAL_DEFAULT = Locale.getDefault();

    private I18nStartup() {
    }

    public static UiLanguage initialize() {
        UiLanguage language = resolve(System.getProperty(LOCALE_PROPERTY),
                LocaleSettings.read(PathManager.getConfigDir()), osLanguages());
        I18n.initialize(language);
        return language;
    }

    /**
     * Returns the preferred system languages listed in {@value #OS_LANGUAGES_PROPERTY}, or else the JVM's original
     * default locale.
     */
    public static List<String> osLanguages() {
        String property = System.getProperty(OS_LANGUAGES_PROPERTY);
        return property != null ? parseLanguageList(property) : List.of(ORIGINAL_DEFAULT.toLanguageTag());
    }

    static UiLanguage resolve(String localeProperty, String setting, List<String> osLanguages) {
        return UiLanguage.fromTag(localeProperty)
                .orElseGet(() -> LanguageNegotiator.negotiate(setting, osLanguages));
    }

    public static List<String> parseLanguageList(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }
}
