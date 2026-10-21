package com.jdesk.core.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides the UI language from the language setting and the preferred system languages. Other runtimes implement the
 * same rules; keep them in step through the shared cases in src/test/resources/i18n/negotiation-cases.json.
 */
public final class LanguageNegotiator {
    public static final String SYSTEM = "system";
    private static final Set<String> TRADITIONAL_REGIONS = Set.of("TW", "HK", "MO");

    private LanguageNegotiator() {
    }

    /**
     * Returns the UI language for the setting and the system languages. A setting of en-XA gives PSEUDO either way;
     * {@code -Djdesk.i18n.pseudo} only decides whether the language settings list it.
     */
    public static UiLanguage negotiate(String setting, List<String> osLanguages) {
        if (setting != null && !setting.isBlank() && !SYSTEM.equalsIgnoreCase(setting.trim())) {
            Optional<UiLanguage> explicit = UiLanguage.fromTag(setting);
            if (explicit.isPresent()) {
                return explicit.get();
            }
        }
        List<String> tags = osLanguages == null ? List.of() : osLanguages;
        for (String tag : tags) {
            Optional<UiLanguage> direct = direct(subtags(tag));
            if (direct.isPresent()) {
                return direct.get();
            }
        }
        for (String tag : tags) {
            Subtags parsed = subtags(tag);
            if (parsed.language().equals("zh") && isTraditional(parsed)) {
                return UiLanguage.ZH_CN;
            }
        }
        return UiLanguage.EN;
    }

    /**
     * Returns which glyph set Han characters use: ja or zh for those UI languages, otherwise the first Chinese or
     * Japanese system language, or zh if there is none.
     */
    public static String cjkFallback(UiLanguage language, List<String> osLanguages) {
        if (language == UiLanguage.JA) {
            return "ja";
        }
        if (language == UiLanguage.ZH_CN) {
            return "zh";
        }
        for (String tag : osLanguages == null ? List.<String>of() : osLanguages) {
            String primary = subtags(tag).language();
            if (primary.equals("ja") || primary.equals("zh")) {
                return primary;
            }
        }
        return "zh";
    }

    private static Optional<UiLanguage> direct(Subtags tag) {
        return switch (tag.language()) {
            case "en" -> Optional.of(UiLanguage.EN);
            case "ja" -> Optional.of(UiLanguage.JA);
            case "zh" -> isSimplified(tag) ? Optional.of(UiLanguage.ZH_CN) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static boolean isSimplified(Subtags tag) {
        if (tag.script() != null) {
            return tag.script().equals("hans");
        }
        return tag.region() == null || !TRADITIONAL_REGIONS.contains(tag.region());
    }

    private static boolean isTraditional(Subtags tag) {
        if (tag.script() != null) {
            return tag.script().equals("hant");
        }
        return tag.region() != null && TRADITIONAL_REGIONS.contains(tag.region());
    }

    private static Subtags subtags(String tag) {
        String[] parts = (tag == null ? "" : tag.trim()).split("[-_]", -1);
        String language = parts[0].toLowerCase(Locale.ROOT);
        String script = null;
        String region = null;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (script == null && region == null && part.length() == 4 && part.chars().allMatch(Character::isLetter)) {
                script = part.toLowerCase(Locale.ROOT);
            } else if (region == null && isRegion(part)) {
                region = part.toUpperCase(Locale.ROOT);
            }
        }
        return new Subtags(language, script, region);
    }

    private static boolean isRegion(String part) {
        return part.length() == 2 && part.chars().allMatch(Character::isLetter)
                || part.length() == 3 && part.chars().allMatch(Character::isDigit);
    }

    private record Subtags(String language, String script, String region) {
    }
}
