package com.jdesk.frame.ui;

import com.jdesk.core.i18n.UiLanguage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdfAboutDialogDateTest {

    @Test
    void releaseDateFollowsLanguage() {
        assertEquals("2026年9月27日", JdfAboutDialog.formatReleaseDate("2026-09-27", UiLanguage.ZH_CN));
        assertEquals("2026年9月27日", JdfAboutDialog.formatReleaseDate("2026-09-27", UiLanguage.JA));
        assertEquals("September 27, 2026", JdfAboutDialog.formatReleaseDate("2026-09-27", UiLanguage.EN));
        assertEquals("not-a-date", JdfAboutDialog.formatReleaseDate("not-a-date", UiLanguage.EN));
    }
}
