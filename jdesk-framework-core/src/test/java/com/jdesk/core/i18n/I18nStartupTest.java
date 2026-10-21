package com.jdesk.core.i18n;

import com.jdesk.core.app.PathManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class I18nStartupTest {
    private String savedLocale;
    private String savedOsLanguages;

    @BeforeEach
    void setUp() {
        savedLocale = System.clearProperty(I18nStartup.LOCALE_PROPERTY);
        savedOsLanguages = System.clearProperty(I18nStartup.OS_LANGUAGES_PROPERTY);
        I18n.resetForTests();
    }

    @AfterEach
    void tearDown() {
        restoreProperty(I18nStartup.LOCALE_PROPERTY, savedLocale);
        restoreProperty(I18nStartup.OS_LANGUAGES_PROPERTY, savedOsLanguages);
        I18n.resetForTests();
        PathManager.resetForTests();
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void launcherPropertyWins() {
        assertEquals(UiLanguage.JA, I18nStartup.resolve("ja", "en", List.of("zh-CN")));
    }

    @Test
    void invalidPropertyFallsBackToNegotiation() {
        assertEquals(UiLanguage.ZH_CN, I18nStartup.resolve("klingon", "system", List.of("zh-CN")));
        assertEquals(UiLanguage.EN, I18nStartup.resolve(null, "en", List.of("ja-JP")));
    }

    @Test
    void parsesCommaSeparatedList() {
        assertEquals(List.of("ja-JP", "en-US"), I18nStartup.parseLanguageList(" ja-JP, en-US ,"));
        assertEquals(List.of(), I18nStartup.parseLanguageList(""));
    }

    @Test
    void initializeAppliesSettingsFileButStillReportsOriginalSystemLocale(@TempDir Path home) throws Exception {
        Locale original = Locale.getDefault();
        UiLanguage chosen = "ja".equals(original.getLanguage()) ? UiLanguage.EN : UiLanguage.JA;
        PathManager.setHomePathForTests(home);
        LocaleSettings.write(PathManager.getConfigDir(), chosen.tag());

        assertEquals(chosen, I18nStartup.initialize());
        assertEquals(chosen, I18n.current());
        assertEquals(chosen.locale(), Locale.getDefault());
        assertEquals(List.of(original.toLanguageTag()), I18nStartup.osLanguages());
    }
}
