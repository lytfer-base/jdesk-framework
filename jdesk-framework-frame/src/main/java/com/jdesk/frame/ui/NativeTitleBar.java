package com.jdesk.frame.ui;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Window;
import java.util.Locale;

/**
 * Switches the system-drawn title bar between light and dark with DWM's {@code DWMWA_USE_IMMERSIVE_DARK_MODE} on
 * Windows, so the title bar, frame and system buttons follow the theme. It matters for windows without a
 * {@link JdfHeader}, such as dialogs; a JBR custom title bar shows no effect. It also rounds heavyweight popups on
 * Windows 11 ({@link #roundPopupCorners}). Everything is a no-op off Windows, without DWM or without a native handle.
 */
public final class NativeTitleBar {

    private static final Logger LOG = LoggerFactory.getLogger(NativeTitleBar.class);

    /** Attribute ID from Windows 10 build 18985 on. */
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

    /** Older attribute ID for Windows 10 1809 (build 17763) up to 18985; there both IDs must be tried. */
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;

    /** A {@code BOOL} is 4 bytes. */
    private static final int BOOL_SIZE = 4;

    /** Corner and border attributes from Windows 11 build 22000; 2 = standard rounding (8 px), 3 = small (4 px). */
    private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private static final int DWMWA_BORDER_COLOR = 34;
    private static final int DWMWCP_ROUND = 2;

    /** {@code DwmSetWindowAttribute}, or null off Windows or without DWM, which makes the class a no-op. */
    private static final Function SET_WINDOW_ATTRIBUTE = loadSetWindowAttribute();

    private NativeTitleBar() {
    }

    /** Draws the window's system title bar dark or light; the window must be displayable and may already be shown. */
    public static void setDarkMode(Window window, boolean dark) {
        if (SET_WINDOW_ATTRIBUTE == null || window == null || !window.isDisplayable()) {
            return;
        }
        try {
            Pointer hwnd = Native.getWindowPointer(window);
            if (hwnd == null) {
                return;
            }
            IntByReference value = new IntByReference(dark ? 1 : 0);
            // Try the new ID, then the old one for 1809 to 1903; if both fail, keep the light title bar silently.
            int result = call(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value);
            if (result != 0) {
                call(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, value);
            }
        }
        catch (Throwable failure) {
            LOG.debug("Failed to set immersive dark mode on the native title bar", failure);
        }
    }

    /** Applies the mode to every displayable window in the process, as after a theme switch. */
    public static void setDarkModeForAllWindows(boolean dark) {
        if (SET_WINDOW_ATTRIBUTE == null) {
            return;
        }
        for (Window window : Window.getWindows()) {
            setDarkMode(window, dark);
        }
    }

    /**
     * Has DWM draw rounded corners, the system shadow and a 1 px border for a heavyweight popup. Returns true on
     * success, so the caller can skip its own border; false before Windows 11, without DWM or without a native handle.
     */
    public static boolean roundPopupCorners(Window window, Color border) {
        if (SET_WINDOW_ATTRIBUTE == null || window == null || !window.isDisplayable()) {
            return false;
        }
        try {
            Pointer hwnd = Native.getWindowPointer(window);
            if (hwnd == null) {
                return false;
            }
            if (call(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, new IntByReference(DWMWCP_ROUND)) != 0) {
                return false;
            }
            if (border != null) {
                int colorRef = (border.getBlue() << 16) | (border.getGreen() << 8) | border.getRed();
                call(hwnd, DWMWA_BORDER_COLOR, new IntByReference(colorRef));
            }
            return true;
        }
        catch (Throwable failure) {
            LOG.debug("Failed to round the popup window corners", failure);
            return false;
        }
    }

    private static int call(Pointer hwnd, int attribute, IntByReference value) {
        return SET_WINDOW_ATTRIBUTE.invokeInt(new Object[]{hwnd, attribute, value, BOOL_SIZE});
    }

    private static Function loadSetWindowAttribute() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        try {
            return Function.getFunction("dwmapi", "DwmSetWindowAttribute");
        }
        catch (Throwable failure) {
            LOG.debug("dwmapi!DwmSetWindowAttribute is not available", failure);
            return null;
        }
    }
}
