package com.jdesk.frame.i18n;

import com.jdesk.core.i18n.UiLanguage;

import javax.swing.JComponent;
import javax.swing.UIManager;

/** Makes the UI language the Swing default locale, which JDK built-in dialogs follow. Must be called on the EDT. */
public final class SwingLocaleSupport {
    private SwingLocaleSupport() {
    }

    public static void applyDefaults(UiLanguage language) {
        JComponent.setDefaultLocale(language.locale());
        UIManager.getDefaults().setDefaultLocale(language.locale());
    }
}
