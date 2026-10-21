package com.jdesk.frame.i18n;

import com.jdesk.core.i18n.UiLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwingLocaleSupportTest {
    private final Locale savedComponent = JComponent.getDefaultLocale();
    private final Locale savedDefaults = UIManager.getDefaults().getDefaultLocale();

    @AfterEach
    void restore() {
        JComponent.setDefaultLocale(savedComponent);
        UIManager.getDefaults().setDefaultLocale(savedDefaults);
    }

    @Test
    void appliesComponentAndUiDefaultsLocale() {
        SwingLocaleSupport.applyDefaults(UiLanguage.JA);
        assertEquals(new Locale("ja", "JP"), JComponent.getDefaultLocale());
        assertEquals(new Locale("ja", "JP"), UIManager.getDefaults().getDefaultLocale());
    }
}
