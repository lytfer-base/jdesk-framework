package com.jdesk.frame.state;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure function tests: no Swing and no display needed, so they run on headless CI. */
class WindowBoundsSanitizerTest {

    /** A width or height of 0 or less means no size constraint. */
    private static final Dimension NO_MIN = new Dimension(0, 0);
    private static final Rectangle PRIMARY = new Rectangle(0, 0, 1920, 1080);

    @Test
    void keepsBoundsThatAreFullyInsideScreen() {
        Rectangle saved = new Rectangle(100, 100, 800, 600);

        assertEquals(new Rectangle(100, 100, 800, 600),
                WindowBoundsSanitizer.sanitize(saved, List.of(PRIMARY), NO_MIN));
    }

    @Test
    void returnsNullWhenBoundsAreFullyOffScreen() {
        // The second screen is gone: the saved bounds at x=3000 are off the primary.
        Rectangle saved = new Rectangle(3000, 100, 800, 600);

        assertNull(WindowBoundsSanitizer.sanitize(saved, List.of(PRIMARY), NO_MIN));
    }

    /**
     * A removed diagonal screen misses on both axes, so Rectangle.intersection has negative width and height and a
     * positive area; without the isEmpty() check in pickScreen that screen would win as the largest overlap.
     */
    @Test
    void returnsNullWhenBoundsAreOnAnUnpluggedDiagonalScreen() {
        Rectangle saved = new Rectangle(3000, 2000, 800, 600);

        assertNull(WindowBoundsSanitizer.sanitize(saved, List.of(PRIMARY), NO_MIN));
    }

    @Test
    void returnsNullWhenNoScreensAvailable() {
        assertNull(WindowBoundsSanitizer.sanitize(new Rectangle(0, 0, 800, 600), List.of(), NO_MIN));
    }

    @Test
    void returnsNullWhenSavedBoundsAreDegenerate() {
        assertNull(WindowBoundsSanitizer.sanitize(new Rectangle(0, 0, 0, 0), List.of(PRIMARY), NO_MIN));
    }

    @Test
    void clampsPartiallyOffScreenBoundsBackInside() {
        // The bottom-right corner is out: x+w=2400 > 1920, y+h=1400 > 1080.
        Rectangle saved = new Rectangle(1600, 800, 800, 600);

        Rectangle result = WindowBoundsSanitizer.sanitize(saved, List.of(PRIMARY), NO_MIN);

        assertEquals(new Rectangle(1120, 480, 800, 600), result);
    }

    @Test
    void growsBoundsUpToMinimumSize() {
        Rectangle saved = new Rectangle(100, 100, 300, 200);

        Rectangle result = WindowBoundsSanitizer.sanitize(saved, List.of(PRIMARY), new Dimension(640, 480));

        assertEquals(new Rectangle(100, 100, 640, 480), result);
    }

    @Test
    void shrinksBoundsLargerThanScreen() {
        // Last maximized on a 4K screen, now on 1080p.
        Rectangle saved = new Rectangle(0, 0, 3000, 2000);

        Rectangle result = WindowBoundsSanitizer.sanitize(saved, List.of(PRIMARY), NO_MIN);

        assertEquals(new Rectangle(0, 0, 1920, 1080), result);
    }

    /**
     * Restoring a window with a 1280x900 minimum on a 1024x768 screen (1920x1080 at 175%): the screen wins, since a
     * window larger than the screen has no valid position, while the screen size keeps the title bar reachable.
     */
    @Test
    void capsAtScreenSizeWhenMinimumExceedsScreen() {
        Rectangle small = new Rectangle(0, 0, 1024, 768);

        Rectangle result = WindowBoundsSanitizer.sanitize(
                new Rectangle(100, 100, 800, 600), List.of(small), new Dimension(1280, 900));

        assertEquals(new Rectangle(0, 0, 1024, 768), result);
    }

    @Test
    void reclampsPositionAfterGrowingToMinimumSize() {
        // After growing to the minimum, clamp the position again or the corner ends up off screen.
        Rectangle result = WindowBoundsSanitizer.sanitize(
                new Rectangle(1700, 900, 200, 100), List.of(PRIMARY), new Dimension(640, 480));

        assertEquals(new Rectangle(1280, 600, 640, 480), result);
    }

    @Test
    void doesNotMutateCallerBounds() {
        Rectangle saved = new Rectangle(1600, 800, 800, 600);

        Rectangle result = WindowBoundsSanitizer.sanitize(saved, List.of(PRIMARY), NO_MIN);

        assertNotSame(saved, result);
        assertEquals(new Rectangle(1600, 800, 800, 600), saved);
    }

    @Test
    void picksScreenWithLargestOverlapWhenBoundsSpanTwoScreens() {
        Rectangle secondary = new Rectangle(1920, 0, 1920, 1080);
        // Overlap is 120*600=72000 on the primary and 680*600=408000 on the secondary, so the secondary wins.
        Rectangle saved = new Rectangle(1800, 100, 800, 600);

        Rectangle result = WindowBoundsSanitizer.sanitize(saved, List.of(PRIMARY, secondary), NO_MIN);

        assertEquals(new Rectangle(1920, 100, 800, 600), result);
    }
}
