package com.jdesk.frame.webview.jcef;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jdesk.frame.webview.bridge.WebBridgeOptions;
import com.jdesk.frame.webview.bridge.WebBridgeSession;
import com.jdesk.frame.webview.bridge.impl.WebBridgeServiceImpl;
import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Script delivery before and after the native browser exists: bridge emits are fire-and-forget on both backends. */
final class JcefWebViewTest {

    @Test
    void emitBeforeTheNativeBrowserExistsIsDroppedInsteadOfThrowing() {
        List<String> executed = new ArrayList<>();
        JcefWebView view = new JcefWebView(recordingBrowser(executed), new JPanel(), ignored -> {
        });
        WebBridgeSession session = new WebBridgeServiceImpl().attach(view, WebBridgeOptions.defaults());

        assertDoesNotThrow(() -> session.emit("dashboard.visibilityChanged",
                JsonNodeFactory.instance.objectNode().put("visible", false)));
        assertEquals(List.of(), executed, "no script before the native browser exists");

        view.nativeCreated();
        session.emit("dashboard.visibilityChanged", JsonNodeFactory.instance.objectNode().put("visible", true));

        assertEquals(1, executed.size(), "an emit after creation must be delivered");
        assertTrue(executed.get(0).contains("window.__jdeskBridgeEmit({\"event\":\"dashboard.visibilityChanged\""));
    }

    /** Fake browser that captures executeJavaScript calls; other methods return defaults. */
    private static CefBrowser recordingBrowser(List<String> executed) {
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(), new Class<?>[]{CefBrowser.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeJavaScript" -> {
                        executed.add((String) args[0]);
                        yield null;
                    }
                    case "getURL" -> "about:blank";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "RecordingCefBrowser";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == long.class || type == double.class || type == float.class
                || type == short.class || type == byte.class || type == char.class) {
            return java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(type, 1), 0);
        }
        return null;
    }
}
