package com.jdesk.frame.settings;

import com.jdesk.core.app.Application;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.UiLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Supplier-based group names for descriptor settings: the group and its generated page follow the UI language. */
class SettingsManagerLocaleTest {
    @BeforeEach
    void registerFrameMessages() {
        I18n.resetForTests();
        I18n.registerRoot("jdesk/i18n", SettingsManagerLocaleTest.class.getClassLoader(), UiLanguage.EN);
    }

    @AfterEach
    void restore() {
        I18n.resetForTests();
    }

    @Test
    void descriptorGroupAndGeneratedPageFollowTheLanguage() {
        SettingsManager manager = new SettingsManager((Application) null);
        manager.registerSetting(SettingDescriptor.booleanSetting("demo.compact", "Compact", false)
                .group("demo", () -> I18n.message("frame.settings.group.general"))
                .build());
        SettingsGroup group = manager.getRootGroup().getChildren().stream()
                .map(SettingsGroup.class::cast)
                .filter(candidate -> candidate.getId().equals("demo"))
                .findFirst()
                .orElseThrow();
        Configurable page = group.collectConfigurables().get(0);
        assertEquals("通用", group.getDisplayName());

        I18n.switchTo(UiLanguage.EN);

        assertEquals("General", group.getDisplayName());
        assertEquals("General", page.getDisplayName());
    }
}
