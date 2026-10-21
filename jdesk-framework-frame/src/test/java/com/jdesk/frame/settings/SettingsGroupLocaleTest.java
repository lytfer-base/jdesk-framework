package com.jdesk.frame.settings;

import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.UiLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsGroupLocaleTest {
    @BeforeEach
    void registerFrameMessages() {
        I18n.resetForTests();
        I18n.registerRoot("jdesk/i18n", SettingsGroupLocaleTest.class.getClassLoader(), UiLanguage.EN);
    }

    @AfterEach
    void restore() {
        I18n.resetForTests();
    }

    @Test
    void groupNameFollowsTheCurrentLanguage() {
        SettingsGroup group = new SettingsGroup("appearance", () -> I18n.message("frame.settings.group.appearance"));
        assertEquals("外观", group.getDisplayName());
        I18n.switchTo(UiLanguage.EN);
        assertEquals("Appearance", group.getDisplayName());
    }
}
