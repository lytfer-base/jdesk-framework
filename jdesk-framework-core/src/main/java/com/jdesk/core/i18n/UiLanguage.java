package com.jdesk.core.i18n;

import java.util.Locale;
import java.util.Optional;

/**
 * UI languages with their BCP 47 tag, Java locale and message file suffix. PSEUDO is for development and derives its
 * text from English.
 */
public enum UiLanguage {
    EN("en", "en", "US", "en"),
    ZH_CN("zh-CN", "zh", "CN", "zh_CN"),
    JA("ja", "ja", "JP", "ja"),
    PSEUDO("en-XA", "en", "XA", "en");

    private final String tag;
    private final String language;
    private final String country;
    private final String fileSuffix;

    UiLanguage(String tag, String language, String country, String fileSuffix) {
        this.tag = tag;
        this.language = language;
        this.country = country;
        this.fileSuffix = fileSuffix;
    }

    public String tag() {
        return tag;
    }

    public Locale locale() {
        return new Locale(language, country);
    }

    public String fileSuffix() {
        return fileSuffix;
    }

    public boolean isPseudo() {
        return this == PSEUDO;
    }

    /** Finds the language for a tag, ignoring case and treating {@code _} as {@code -}. */
    public static Optional<UiLanguage> fromTag(String tag) {
        if (tag == null) {
            return Optional.empty();
        }
        String normalized = tag.trim().replace('_', '-');
        for (UiLanguage language : values()) {
            if (language.tag.equalsIgnoreCase(normalized)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }
}
