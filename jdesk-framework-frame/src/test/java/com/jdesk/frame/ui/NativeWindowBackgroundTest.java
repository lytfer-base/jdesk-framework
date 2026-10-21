package com.jdesk.frame.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class NativeWindowBackgroundTest {

    @Test
    void classBrushIsInstalledOncePeerExists() {
        assumeTrue(isWindows(), "the window class brush is a Win32 concept");
        assumeTrue(!GraphicsEnvironment.isHeadless(), "creating a native window needs a graphics environment");

        JdfWindowFrame frame = new JdfWindowFrame("brush-probe");
        try {
            frame.addNotify(); // Creates the native handle without showing; addNotify applies the brush itself.
            // The brush is shared class state, so assert only that it is set; this guards the call chain as a whole.
            assertNotEquals(0, NativeWindowBackground.readClassBackgroundBrush(frame),
                    "the window class brush should be set; it paints the first frame of a new window");
        }
        finally {
            frame.removeNotify();
            frame.dispose();
        }
    }

    @Test
    void reusedColorReusesTheSameBrush() {
        assumeTrue(isWindows(), "the window class brush is a Win32 concept");
        assumeTrue(!GraphicsEnvironment.isHeadless(), "creating a native window needs a graphics environment");

        JdfWindowFrame frame = new JdfWindowFrame("brush-cache-probe");
        try {
            frame.addNotify();
            // Brushes are never deleted, so getting the same handle back for a used color proves reuse, not a leak.
            NativeWindowBackground.apply(frame, new Color(0x2B, 0x2D, 0x30));
            long dark = NativeWindowBackground.readClassBackgroundBrush(frame);
            assertNotEquals(0, dark, "the brush must exist first, or the equality check below proves nothing");

            NativeWindowBackground.apply(frame, new Color(0xF7, 0xF8, 0xFA));
            NativeWindowBackground.apply(frame, new Color(0xFB, 0xFC, 0xFC));
            NativeWindowBackground.apply(frame, new Color(0x2B, 0x2D, 0x30));

            assertEquals(dark, NativeWindowBackground.readClassBackgroundBrush(frame),
                    "switching back to a used color should reuse the same brush");
        }
        finally {
            frame.removeNotify();
            frame.dispose();
        }
    }

    @Test
    void applyIsSafeWithoutNativeHandle() {
        // Off Windows, without a handle or with null, apply is a no-op; a brush must never stop a window opening.
        assertDoesNotThrow(() -> NativeWindowBackground.apply(null, Color.BLACK));
        JdfWindowFrame frame = new JdfWindowFrame("no-peer");
        try {
            assertDoesNotThrow(() -> NativeWindowBackground.apply(frame, null));
            assertDoesNotThrow(() -> NativeWindowBackground.apply(frame, Color.BLACK));
        }
        finally {
            frame.dispose();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
