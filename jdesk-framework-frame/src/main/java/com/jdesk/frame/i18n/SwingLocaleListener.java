package com.jdesk.frame.i18n;

import com.jdesk.core.app.Application;
import com.jdesk.core.i18n.LocaleListener;
import com.jdesk.core.i18n.UiLanguage;
import com.jdesk.frame.laf.JdfLafManager;
import com.jdesk.frame.ui.scale.JdfFonts;

import javax.swing.SwingUtilities;

/** Updates the Swing default locale and the UI fonts on the EDT when the language changes. */
public final class SwingLocaleListener implements LocaleListener {
    private final JdfLafManager lafManager;

    SwingLocaleListener(JdfLafManager lafManager) {
        this.lafManager = lafManager;
    }

    /** Subscribes to language changes on the application message bus; the connection is disposed with the bus. */
    public static void install(Application application) {
        application.getMessageBus().connect()
                .subscribe(LocaleListener.class, new SwingLocaleListener(application.getService(JdfLafManager.class)));
    }

    @Override
    public void localeChanged(UiLanguage oldLanguage, UiLanguage newLanguage) {
        // Don't use invokeAndWait: I18n.switchTo still holds its class lock, so a switch on the EDT would deadlock.
        SwingUtilities.invokeLater(() -> {
            SwingLocaleSupport.applyDefaults(newLanguage);
            JdfFonts.setLanguage(newLanguage);
            lafManager.refreshFontsForLanguage();
        });
    }
}
