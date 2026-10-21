package com.jdesk.frame.i18n;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.I18nStartup;
import com.jdesk.core.i18n.LanguageNegotiator;
import com.jdesk.core.i18n.LocaleListener;
import com.jdesk.core.i18n.UiLanguage;
import com.jdesk.core.message.MessageBus;
import com.jdesk.frame.webview.bridge.WebBridgeResponse;
import com.jdesk.frame.webview.bridge.WebBridgeSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/** Language channel for pages: locale.current to pull, locale.changed to push; subscriptions end with the session. */
public final class LocaleBridge {
    public static final String CURRENT_METHOD = "locale.current";
    public static final String CHANGED_EVENT = "locale.changed";
    private static final Logger LOG = LoggerFactory.getLogger(LocaleBridge.class);

    private LocaleBridge() {
    }

    /**
     * Registers locale.current on the session and subscribes it to language changes; without an application, only
     * the method is registered.
     */
    public static void install(WebBridgeSession session) {
        session.register(CURRENT_METHOD, request -> CompletableFuture.completedFuture(
                WebBridgeResponse.success(request.id(), payload(I18n.current()))));
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app == null || app.isDisposed()) {
            return;
        }
        MessageBus bus = app.getMessageBus();
        if (bus == null || bus.isDisposed()) {
            return;
        }
        bus.connect(session).subscribe(LocaleListener.class, (oldLanguage, newLanguage) -> emit(session, newLanguage));
    }

    static ObjectNode payload(UiLanguage language) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("language", language.tag());
        node.put("cjkFallback", LanguageNegotiator.cjkFallback(language, I18nStartup.osLanguages()));
        return node;
    }

    private static void emit(WebBridgeSession session, UiLanguage language) {
        try {
            session.emit(CHANGED_EVENT, payload(language));
        } catch (RuntimeException e) {
            LOG.warn("locale.bridge.emit.failed language={}", language.tag(), e);
        }
    }
}
