package com.jdesk.frame.ui.scale;

import com.jdesk.core.i18n.UiLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdfFontsTest {

    @AfterEach
    void clearGlobalState() {
        JdfFonts.resetForTests();
        // Reinstall the default-language fonts so fonts set here don't leak into later tests in the same JVM.
        JdfFonts.installDefaults();
        JdfFonts.resetForTests();
        JdfScale.resetForTests();
    }

    @Test
    void installedLabelFontCanDisplayChineseText() {
        JdfFonts.resetForTests();
        JdfScale.resetForTests();

        JdfFonts.installDefaults();

        Font labelFont = UIManager.getFont("Label.font");
        assertTrue(labelFont.canDisplay('灵'), () -> "Label font cannot display Chinese: " + labelFont);
        assertTrue(labelFont.canDisplay('中'), () -> "Label font cannot display Chinese: " + labelFont);
    }

    @Test
    void windowsFamiliesFollowLanguage() {
        assertEquals(List.of("Yu Gothic UI", "Meiryo UI", "Meiryo", "MS UI Gothic"), JdfFonts.windowsFamilies(UiLanguage.JA));
        assertEquals(List.of("Microsoft YaHei UI", "Microsoft YaHei"), JdfFonts.windowsFamilies(UiLanguage.ZH_CN));
        assertEquals(List.of("Segoe UI"), JdfFonts.windowsFamilies(UiLanguage.EN));
    }

    @Test
    void japaneseUiUsesJapaneseFontWhenInstalled() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Assumptions.assumeTrue(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)).contains("Yu Gothic UI"));
        JdfFonts.setLanguage(UiLanguage.JA);
        JdfFonts.installDefaults();
        Font label = UIManager.getDefaults().getFont("Label.font");
        assertEquals("Yu Gothic UI", label.getFamily(Locale.ROOT));
        assertTrue(label.canDisplay('あ'));
    }

    @Test
    void englishUiUsesSegoeWithCjkFallback() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        JdfFonts.setLanguage(UiLanguage.EN);
        JdfFonts.installDefaults();
        Font label = UIManager.getDefaults().getFont("Label.font");
        assertEquals("Segoe UI", label.getFamily(Locale.ROOT));
        assertTrue(label.canDisplay('中'), "the English UI needs composite font fallback for Chinese");
    }
}
