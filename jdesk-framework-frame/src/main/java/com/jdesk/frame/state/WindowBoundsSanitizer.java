package com.jdesk.frame.state;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;

/**
 * Fits saved window bounds onto the current screens, which may have lost a monitor or shrunk since the bounds were
 * saved, so that a restored window never lands out of reach. Pure functions without Swing or
 * {@code GraphicsEnvironment}, so tests can run without a display.
 */
public final class WindowBoundsSanitizer {

    private WindowBoundsSanitizer() {
    }

    /**
     * Clamps saved bounds into the visible screens. Returns {@code null} when there is no screen, the bounds are
     * degenerate or they meet no screen; the caller should then keep the window's default size and position.
     *
     * @param screens work areas of the visible screens, without the taskbar; full screen bounds would put the window
     *                under the taskbar
     * @param min     minimum size; {@code null} or a non-positive width or height leaves that axis unconstrained
     */
    public static Rectangle sanitize(Rectangle saved, List<Rectangle> screens, Dimension min) {
        if (saved == null || screens == null || screens.isEmpty()) {
            return null;
        }
        if (saved.width <= 0 || saved.height <= 0) {
            return null;
        }
        Rectangle target = pickScreen(saved, screens);
        if (target == null) {
            return null;
        }

        Rectangle result = new Rectangle(saved);
        if (min != null && min.width > 0) {
            result.width = Math.max(result.width, min.width);
        }
        if (min != null && min.height > 0) {
            result.height = Math.max(result.height, min.height);
        }
        // Clamp size before position; the screen limit beats min, since a reachable title bar matters more.
        result.width = Math.min(result.width, target.width);
        result.height = Math.min(result.height, target.height);
        result.x = clamp(result.x, target.x, target.x + target.width - result.width);
        result.y = clamp(result.y, target.y, target.y + target.height - result.height);
        return result;
    }

    /** Returns the screen with the largest overlap, or {@code null} if none overlaps (say, a monitor was unplugged). */
    private static Rectangle pickScreen(Rectangle saved, List<Rectangle> screens) {
        Rectangle best = null;
        long bestArea = 0L;
        for (Rectangle screen : screens) {
            Rectangle overlap = screen.intersection(saved);
            if (overlap.isEmpty()) {
                continue;
            }
            long area = (long) overlap.width * overlap.height;
            if (area > bestArea) {
                bestArea = area;
                best = screen;
            }
        }
        return best;
    }

    private static int clamp(int value, int low, int high) {
        return Math.min(Math.max(value, low), high);
    }
}
