package com.jdesk.frame.ui;

import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.WindowProc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsConfiguration;
import java.awt.Window;
import java.util.Arrays;
import java.util.List;

/**
 * Native maximum size for a window, enforced by handling {@code WM_GETMINMAXINFO}: AWT passes only the minimum
 * size to Windows, {@code setMaximumSize} does nothing for top-level windows, and a later {@code setSize} loses to
 * the native resize loop. Does nothing off Windows, without JNA or without a native handle.
 * Keep the instance and {@link #dispose()} it before the window goes, or native calls would hit freed memory.
 */
public final class NativeWindowSizeLimit {

    private static final Logger LOG = LoggerFactory.getLogger(NativeWindowSizeLimit.class);

    private static final int GWL_WNDPROC = -4;
    private static final int WM_GETMINMAXINFO = 0x0024;

    private final HWND hwnd;
    private final Pointer previousProc;
    /** Must stay strongly referenced: native code holds only a function pointer to it. */
    private final WindowProc proc;

    private volatile int maxWidth;
    private volatile int maxHeight;
    private volatile double scale = 1;
    private boolean disposed;

    private NativeWindowSizeLimit(HWND hwnd, WindowProc proc, Pointer previousProc) {
        this.hwnd = hwnd;
        this.proc = proc;
        this.previousProc = previousProc;
    }

    /**
     * Subclasses the window procedure so that {@link #setMaxSize} can set limits; the window must be displayable.
     * Returns {@code null} where that isn't possible (off Windows, no handle, no JNA); carry on without a limit then.
     */
    public static NativeWindowSizeLimit install(Window window) {
        if (window == null || !window.isDisplayable() || !NativeFileDialogs.isWindows()) {
            return null;
        }
        try {
            Pointer handle = Native.getComponentPointer(window);
            if (handle == null) {
                return null;
            }
            HWND hwnd = new HWND(handle);
            NativeWindowSizeLimit[] holder = new NativeWindowSizeLimit[1];
            WindowProc proc = (wnd, msg, wParam, lParam) -> holder[0].handle(wnd, msg, wParam, lParam);
            // JNA 5.12's User32 has no Callback overload, so pass the function pointer.
            Pointer previous = User32.INSTANCE.SetWindowLongPtr(hwnd, GWL_WNDPROC,
                    CallbackReference.getFunctionPointer(proc));
            if (previous == null) {
                return null;
            }
            holder[0] = new NativeWindowSizeLimit(hwnd, proc, previous);
            holder[0].readScale(window);
            return holder[0];
        } catch (RuntimeException | UnsatisfiedLinkError failure) {
            LOG.info("nativeWindowSizeLimit.install.skipped reason={}", failure.toString());
            return null;
        }
    }

    /**
     * Sets the limit in logical pixels, converted with the scale of the window's screen; {@code <= 0} leaves that
     * axis unlimited. A window already larger shrinks at once.
     */
    public void setMaxSize(Window window, int width, int height) {
        readScale(window);
        maxWidth = width;
        maxHeight = height;
        if (window != null && window.isDisplayable()) {
            int clampedWidth = width > 0 ? Math.min(window.getWidth(), width) : window.getWidth();
            int clampedHeight = height > 0 ? Math.min(window.getHeight(), height) : window.getHeight();
            if (clampedWidth != window.getWidth() || clampedHeight != window.getHeight()) {
                window.setSize(clampedWidth, clampedHeight);
            }
        }
    }

    /** Restores the original window procedure; repeatable. Must run before the window is disposed. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        try {
            User32.INSTANCE.SetWindowLongPtr(hwnd, GWL_WNDPROC, previousProc);
        } catch (RuntimeException failure) {
            LOG.info("nativeWindowSizeLimit.dispose.failed reason={}", failure.toString());
        }
    }

    /**
     * Lets AWT's window procedure fill the structure first and then sets the maximum; in the other order AWT would
     * reset it to the size of the whole virtual screen.
     */
    private LRESULT handle(HWND wnd, int msg, WPARAM wParam, LPARAM lParam) {
        LRESULT result = User32.INSTANCE.CallWindowProc(previousProc, wnd, msg, wParam, lParam);
        if (msg == WM_GETMINMAXINFO && (maxWidth > 0 || maxHeight > 0)) {
            try {
                MINMAXINFO info = new MINMAXINFO(new Pointer(lParam.longValue()));
                info.read();
                if (maxWidth > 0) {
                    info.ptMaxTrackSize.x = physical(maxWidth);
                }
                if (maxHeight > 0) {
                    info.ptMaxTrackSize.y = physical(maxHeight);
                }
                info.write();
            } catch (RuntimeException failure) {
                LOG.info("nativeWindowSizeLimit.minMaxInfo.failed reason={}", failure.toString());
            }
        }
        return result;
    }

    /** {@code MINMAXINFO} is in physical pixels and Java sizes in logical ones; they differ by the screen scale. */
    private int physical(int logical) {
        return (int) Math.round(logical * scale);
    }

    private void readScale(Window window) {
        if (window == null) {
            return;
        }
        GraphicsConfiguration config = window.getGraphicsConfiguration();
        if (config != null) {
            scale = config.getDefaultTransform().getScaleX();
        }
    }

    /** Win32 {@code MINMAXINFO}, which JNA 5.12's {@code WinUser} lacks; keep the field order of the Win32 header. */
    public static final class MINMAXINFO extends Structure {
        public POINT ptReserved;
        public POINT ptMaxSize;
        public POINT ptMaxPosition;
        public POINT ptMinTrackSize;
        public POINT ptMaxTrackSize;

        public MINMAXINFO(Pointer memory) {
            super(memory);
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("ptReserved", "ptMaxSize", "ptMaxPosition", "ptMinTrackSize", "ptMaxTrackSize");
        }
    }
}
