package com.jdesk.frame.i18n;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.LocaleSettings;
import com.jdesk.core.i18n.UiLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageSettingsConfigurableTest {
    @TempDir
    Path home;

    @BeforeEach
    void setUp() {
        PathManager.setHomePathForTests(home);
        I18n.resetForTests();
        I18n.registerRoot("jdesk/i18n", getClass().getClassLoader(), UiLanguage.EN);
    }

    @AfterEach
    void tearDown() {
        PathManager.resetForTests();
        I18n.resetForTests();
    }

    @Test
    void offersSystemAndThreeLanguagesInTheirOwnNames() {
        LanguageSettingsConfigurable page = new LanguageSettingsConfigurable();
        assertEquals(I18n.message("frame.settings.language.followSystem"), page.options().get(0).label());
        assertEquals(I18n.message("frame.settings.language.en"), page.options().get(1).label());
        assertEquals(I18n.message("frame.settings.language.zhCN"), page.options().get(2).label());
        assertEquals(I18n.message("frame.settings.language.ja"), page.options().get(3).label());
        assertEquals(4, page.options().size(), "the pseudo locale appears only with -Djdesk.i18n.pseudo=true");
    }

    @Test
    void applyPersistsAndSwitchesImmediately() throws Exception {
        LanguageSettingsConfigurable page = new LanguageSettingsConfigurable();
        page.createComponent();
        page.reset();
        assertFalse(page.isModified());
        page.select("ja");
        assertTrue(page.isModified());
        page.apply();
        assertEquals("ja", LocaleSettings.read(PathManager.getConfigDir()));
        assertEquals(UiLanguage.JA, I18n.current());
        assertFalse(page.isModified());
    }

    @Test
    void openedPageShowsTheSavedSetting() throws Exception {
        LocaleSettings.write(PathManager.getConfigDir(), "ja");
        LanguageSettingsConfigurable page = new LanguageSettingsConfigurable();
        page.createComponent();
        assertEquals("ja", page.selectedSetting(), "createComponent alone must show the saved value");
        assertFalse(page.isModified());
    }

    @Test
    void savedValueWithoutMatchingOptionOpensAsFollowSystemAndIsNotAChange() throws Exception {
        LocaleSettings.write(PathManager.getConfigDir(), "en-XA");
        LanguageSettingsConfigurable page = new LanguageSettingsConfigurable();
        page.createComponent();
        assertEquals("system", page.selectedSetting());
        assertFalse(page.isModified(), "just opening the page must not make OK rewrite the settings file");
    }
}
