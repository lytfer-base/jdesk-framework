package com.jdesk.frame.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.I18nStartup;
import com.jdesk.core.i18n.UiLanguage;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.frame.boot.JdeskApplication;
import com.jdesk.frame.webview.bridge.WebBridgeHandler;
import com.jdesk.frame.webview.bridge.WebBridgeRequest;
import com.jdesk.frame.webview.bridge.WebBridgeResponse;
import com.jdesk.frame.webview.bridge.WebBridgeSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocaleBridgeTest {
    private String savedOsLanguages;

    @BeforeEach
    void save() {
        savedOsLanguages = System.getProperty(I18nStartup.OS_LANGUAGES_PROPERTY);
    }

    @AfterEach
    void clear() {
        if (savedOsLanguages == null) {
            System.clearProperty(I18nStartup.OS_LANGUAGES_PROPERTY);
        } else {
            System.setProperty(I18nStartup.OS_LANGUAGES_PROPERTY, savedOsLanguages);
        }
    }

    @Test
    void payloadCarriesLanguageAndCjkFallback() {
        System.setProperty(I18nStartup.OS_LANGUAGES_PROPERTY, "en-US,ja-JP");
        ObjectNode english = LocaleBridge.payload(UiLanguage.EN);
        assertEquals("en", english.get("language").asText());
        assertEquals("ja", english.get("cjkFallback").asText());
        assertEquals("zh", LocaleBridge.payload(UiLanguage.ZH_CN).get("cjkFallback").asText());
    }

    @Test
    void pageFollowsLanguageSwitchesUntilTheSessionIsDisposed(@TempDir Path home) {
        PathManager.setHomePathForTests(home);
        Application app = new JdeskApplication(LocaleBridgeTest.class.getClassLoader());
        RecordingSession session = new RecordingSession();
        try {
            app.init();
            I18n.initialize(UiLanguage.ZH_CN);
            LocaleBridge.install(session);

            I18n.switchTo(UiLanguage.JA);
            JsonNode current = session.call(LocaleBridge.CURRENT_METHOD).result();
            Disposer.dispose(session);
            I18n.switchTo(UiLanguage.EN);

            assertEquals("ja", current.get("language").asText());
            assertEquals(List.of("locale.changed:ja"), session.events);
        } finally {
            if (!app.isDisposed()) {
                app.shutdown();
            }
            ApplicationManager.clearApplication(app);
            Disposer.clearAll();
            RuntimeParamRegistry.reset();
            PathManager.resetForTests();
            I18n.resetForTests();
        }
    }

    /** Records registrations and emits without transporting anything. */
    private static final class RecordingSession implements WebBridgeSession {
        private final Map<String, WebBridgeHandler> handlers = new HashMap<>();
        private final List<String> events = new ArrayList<>();

        WebBridgeResponse call(String method) {
            return dispatch(new WebBridgeRequest("1", method, null, null, null)).toCompletableFuture().join();
        }

        @Override
        public void register(String method, WebBridgeHandler handler) {
            handlers.put(method, handler);
        }

        @Override
        public void unregister(String method) {
            handlers.remove(method);
        }

        @Override
        public boolean hasHandler(String method) {
            return handlers.containsKey(method);
        }

        @Override
        public void emit(String event, JsonNode payload) {
            events.add(event + ":" + payload.get("language").asText());
        }

        @Override
        public CompletionStage<WebBridgeResponse> dispatch(WebBridgeRequest request) {
            return handlers.get(request.method()).handle(request);
        }

        @Override
        public void dispose() {
        }
    }
}
