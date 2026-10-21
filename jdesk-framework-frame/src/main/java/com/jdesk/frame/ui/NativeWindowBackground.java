package com.jdesk.frame.ui;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Window;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sets the Win32 background brush ({@code GCLP_HBRBACKGROUND}) of an AWT window class to the theme color. Until the
 * first frame is painted, the native surface shows that brush rather than {@link Window#setBackground}, and AWT
 * leaves it empty, so the opening animation flashes white. The brush is shared by the whole class; off Windows or
 * without a native handle this does nothing.
 * Never DeleteObject a brush: a class not refreshed since may still hold it, so brushes are cached per color.
 */
public final class NativeWindowBackground {

    private static final Logger LOG = LoggerFactory.getLogger(NativeWindowBackground.class);

    /** Background brush index for {@code SetClassLongPtr}. */
    private static final int GCLP_HBRBACKGROUND = -10;

    private static final Function SET_CLASS_LONG_PTR = load("user32", "SetClassLongPtrW");
    private static final Function GET_CLASS_LONG_PTR = load("user32", "GetClassLongPtrW");
    private static final Function CREATE_SOLID_BRUSH = load("gdi32", "CreateSolidBrush");

    /** COLORREF to brush handle, guarded by the class lock; entries stay, so a color used again reuses its brush. */
    private static final Map<Integer, Long> BRUSHES = new HashMap<>();

    private NativeWindowBackground() {
    }

    /**
     * Sets the background brush of the window's class to {@code color}; the window needs a native handle (after
     * {@code addNotify}). Repeated calls are cheap and idempotent.
     */
    public static synchronized void apply(Window window, Color color) {
        if (SET_CLASS_LONG_PTR == null || CREATE_SOLID_BRUSH == null
                || window == null || color == null || !window.isDisplayable()) {
            return;
        }
        try {
            Pointer hwnd = Native.getWindowPointer(window);
            if (hwnd == null) {
                return;
            }
            // COLORREF is 0x00BBGGRR, the reverse of the java.awt.Color channel order.
            int colorRef = (color.getBlue() << 16) | (color.getGreen() << 8) | color.getRed();
            Long brush = BRUSHES.get(colorRef);
            if (brush == null) {
                long created = CREATE_SOLID_BRUSH.invokeLong(new Object[]{colorRef});
                if (created == 0) {
                    return;
                }
                brush = created; // Kept for good; never deleted.
                BRUSHES.put(colorRef, brush);
            }
            SET_CLASS_LONG_PTR.invokeLong(new Object[]{hwnd, GCLP_HBRBACKGROUND, brush});
        }
        catch (Throwable failure) {
            LOG.debug("Failed to set the native class background brush", failure);
        }
    }

    /** Reads the class background brush back, or 0 on failure; for diagnostics and tests. */
    static long readClassBackgroundBrush(Window window) {
        if (GET_CLASS_LONG_PTR == null || window == null || !window.isDisplayable()) {
            return 0;
        }
        try {
            Pointer hwnd = Native.getWindowPointer(window);
            return hwnd == null ? 0
                    : GET_CLASS_LONG_PTR.invokeLong(new Object[]{hwnd, GCLP_HBRBACKGROUND});
        }
        catch (Throwable failure) {
            return 0;
        }
    }

    private static Function load(String library, String name) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        try {
            return Function.getFunction(library, name);
        }
        catch (Throwable failure) {
            LOG.debug("{}!{} is not available", library, name, failure);
            return null;
        }
    }
}
