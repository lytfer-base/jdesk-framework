package com.jdesk.frame.webview.jcef;

import com.jdesk.frame.webview.JdfEditorCommand;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.cef.browser.CefFrame;

/**
 * Sends framework editor commands to JCEF: cut, copy and delete through {@link CefFrame}, paste as a real Ctrl+V.
 * Don't paste with {@code CefFrame.paste()}: without user activation the renderer may read an empty clipboard and
 * replace the selection with nothing. {@code CefBrowser.sendKeyEvent} can't send key combinations, so Ctrl+V goes
 * through the DevTools protocol.
 */
final class JcefEditorCommands {

    private JcefEditorCommands() {
    }

    static void execute(CefBrowser browser, CefDevToolsClient devTools, JdfEditorCommand command) {
        CefFrame frame = browser.getFocusedFrame();
        if (frame == null) {
            frame = browser.getMainFrame();
        }
        switch (command) {
            case CUT -> frame.cut();
            case COPY -> frame.copy();
            case DELETE -> frame.delete();
            case PASTE -> dispatchCtrlV(devTools);
        }
    }

    /** Doesn't wait for the reply: it often never comes, though the keys are already dispatched by then. */
    private static void dispatchCtrlV(CefDevToolsClient devTools) {
        devTools.executeDevToolsMethod("Input.dispatchKeyEvent", keyEvent("rawKeyDown"));
        devTools.executeDevToolsMethod("Input.dispatchKeyEvent", keyEvent("keyUp"));
    }

    private static String keyEvent(String type) {
        return "{\"type\":\"" + type + "\",\"modifiers\":2,\"key\":\"v\",\"code\":\"KeyV\","
                + "\"windowsVirtualKeyCode\":86,\"nativeVirtualKeyCode\":86}";
    }
}
