package com.jdesk.frame.state;

import com.jdesk.core.config.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JFrame;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Capture and restore decisions are pure functions ({@code applyCapture} / {@code planRestore}) tested directly,
 * since the headless gate at the public entry points skips them on CI. Display-gated end-to-end tests cover the
 * wiring that applies a plan to a real {@code JFrame}; they are skipped on headless CI.
 */
class WindowStateServiceTest {

    private static final Rectangle PRIMARY = new Rectangle(0, 0, 1920, 1080);
    private static final Dimension NO_MIN = new Dimension(0, 0);

    private static Rectangle boundsOf(WindowStateService.FrameState state) {
        return new Rectangle(state.x, state.y, state.width, state.height);
    }

    @Test
    void captureUpdatesEverythingWhenWindowIsNormal() {
        WindowStateService.FrameState saved = new WindowStateService.FrameState();

        WindowStateService.applyCapture(saved, new Rectangle(10, 20, 800, 600), Frame.NORMAL, true);

        assertEquals(new Rectangle(10, 20, 800, 600), boundsOf(saved));
        assertEquals(Frame.NORMAL, saved.extendedState);
        assertTrue(saved.visible);
    }

    @Test
    void captureKeepsRestoreBoundsWhenWindowIsMaximized() {
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(10, 20, 800, 600), Frame.NORMAL, true);

        // Maximizing makes getBounds() full screen, but the restore bounds must be kept.
        WindowStateService.applyCapture(saved, new Rectangle(0, 0, 1920, 1080), Frame.MAXIMIZED_BOTH, true);

        assertEquals(new Rectangle(10, 20, 800, 600), boundsOf(saved), "maximizing must not overwrite restore bounds");
        assertEquals(Frame.MAXIMIZED_BOTH, saved.extendedState);
    }

    @Test
    void maximizedOnlyCaptureLeavesNoRestoreBounds() {
        WindowStateService.FrameState saved = new WindowStateService.FrameState();

        // A first run closed while maximized: no NORMAL capture ever happened.
        WindowStateService.applyCapture(saved, new Rectangle(0, 0, 1920, 1080), Frame.MAXIMIZED_BOTH, true);

        assertFalse(saved.hasBounds(), "maximized captures have no restore bounds; restore must not gate on hasBounds");
        assertEquals(Frame.MAXIMIZED_BOTH, saved.extendedState, "but the maximized state must be kept");
    }

    @Test
    void captureClearsMaximizedFlagWhenWindowReturnsToNormal() {
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(10, 20, 800, 600), Frame.NORMAL, true);
        WindowStateService.applyCapture(saved, new Rectangle(0, 0, 1920, 1080), Frame.MAXIMIZED_BOTH, true);

        // Un-maximized and resized before exiting.
        WindowStateService.applyCapture(saved, new Rectangle(30, 40, 1024, 768), Frame.NORMAL, true);

        assertEquals(Frame.NORMAL, saved.extendedState, "un-maximizing must clear the maximized bit");
        assertEquals(new Rectangle(30, 40, 1024, 768), boundsOf(saved), "the normal state should take the new bounds");
    }

    @Test
    void loadStateFallsBackToEmptyStateWhenNull() {
        WindowStateService service = new WindowStateService();

        service.loadState(null);

        assertNotNull(service.getState());
        assertNotNull(service.getState().frames);
    }

    /** Storage gets a snapshot: it may be serialized on another thread, and later captures must not change it. */
    @Test
    void stateHandedToStorageIsASnapshot() {
        WindowStateService service = new WindowStateService();
        WindowStateService.StateData live = new WindowStateService.StateData();
        WindowStateService.FrameState main = frameState(live, "main");
        WindowStateService.applyCapture(main, new Rectangle(10, 20, 800, 600), Frame.NORMAL, true);
        service.loadState(live);

        WindowStateService.StateData forSaving = service.getState();
        WindowStateService.applyCapture(main, new Rectangle(99, 99, 640, 480), Frame.NORMAL, true);

        assertEquals(10, forSaving.frames.get(0).x);
        assertEquals(800, forSaving.frames.get(0).width);
    }

    @Test
    void defaultStateIsEmpty() {
        WindowStateService service = new WindowStateService();

        assertTrue(service.getDefaultState().frames.isEmpty());
    }

    /**
     * Round-trips through the real XML file between two {@link ComponentStore}s; passing objects in memory would skip
     * the Jackson path where window geometry gets lost. No headless gate: it never creates a {@code JFrame}.
     */
    @Test
    void frameStatesSurviveDiskRoundTrip(@TempDir Path configDir) {
        ComponentStore store = new ComponentStore(configDir);
        WindowStateService source = new WindowStateService();
        store.register(source);

        WindowStateService.StateData captured = new WindowStateService.StateData();
        WindowStateService.FrameState records = frameState(captured, "records");
        WindowStateService.applyCapture(records, new Rectangle(10, 20, 800, 600), Frame.NORMAL, true);
        WindowStateService.FrameState materials = frameState(captured, "materials");
        WindowStateService.applyCapture(materials, new Rectangle(30, 40, 1024, 768), Frame.NORMAL, true);
        // Maximized and hidden: a non-default extendedState and visible=false must both survive the XML.
        WindowStateService.applyCapture(materials, new Rectangle(0, 0, 1920, 1080), Frame.MAXIMIZED_BOTH, false);
        source.loadState(captured);
        store.saveAll();

        WindowStateService restarted = new WindowStateService();
        new ComponentStore(configDir).register(restarted);

        assertEquals(2, restarted.getState().frames.size(), "both windows must come back, not one merged entry");
        WindowStateService.FrameState restoredRecords = restored(restarted, "records");
        assertEquals(new Rectangle(10, 20, 800, 600), boundsOf(restoredRecords));
        assertEquals(Frame.NORMAL, restoredRecords.extendedState);
        assertTrue(restoredRecords.visible);

        WindowStateService.FrameState restoredMaterials = restored(restarted, "materials");
        assertEquals(new Rectangle(30, 40, 1024, 768), boundsOf(restoredMaterials), "pre-maximize bounds must survive");
        assertEquals(Frame.MAXIMIZED_BOTH, restoredMaterials.extendedState, "the maximized bit must survive");
        assertFalse(restoredMaterials.visible);
    }

    /** Adds a record for the key to state later passed to {@code loadState}, without creating a JFrame. */
    private static WindowStateService.FrameState frameState(WindowStateService.StateData data, String key) {
        WindowStateService.FrameState created = new WindowStateService.FrameState();
        created.key = key;
        data.frames.add(created);
        return created;
    }

    private static WindowStateService.FrameState restored(WindowStateService service, String key) {
        return service.getState().frames.stream()
                .filter(frame -> key.equals(frame.key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no window record after the round trip: " + key));
    }

    @Test
    void planRestoreAppliesBothWhenRecordHasTrustedBounds() {
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(100, 100, 800, 600), Frame.NORMAL, true);

        WindowStateService.RestorePlan plan = WindowStateService.planRestore(saved, List.of(PRIMARY), NO_MIN);

        assertEquals(new Rectangle(100, 100, 800, 600), plan.bounds());
        assertEquals(Frame.NORMAL, plan.extendedState());
    }

    /**
     * A degenerate saved size, such as a window stored as just its title bar after a display scale change, is lifted
     * to a minimum size on restore; otherwise it would stick forever.
     */
    @Test
    void planRestoreLiftsADegenerateSizeToTheFloor() {
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(569, 213, 184, 30), Frame.NORMAL, true);

        WindowStateService.RestorePlan plan = WindowStateService.planRestore(saved, List.of(PRIMARY), NO_MIN);

        assertTrue(plan.bounds().width >= 320, "width should reach the floor; actual=" + plan.bounds().width);
        assertTrue(plan.bounds().height >= 240, "height should reach the floor; actual=" + plan.bounds().height);
        assertEquals(569, plan.bounds().x, "the position stays");
        assertEquals(213, plan.bounds().y);
    }

    /** The floor only lifts degenerate sizes; a normal size isn't changed by a single pixel. */
    @Test
    void planRestoreLeavesANormalSizeAlone() {
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(100, 100, 460, 760), Frame.NORMAL, true);

        WindowStateService.RestorePlan plan = WindowStateService.planRestore(saved, List.of(PRIMARY), NO_MIN);

        assertEquals(new Rectangle(100, 100, 460, 760), plan.bounds());
    }

    /** On a screen smaller than the floor the screen wins: keeping the title bar reachable matters more. */
    @Test
    void screenLimitStillWinsOverTheFloorOnATinyScreen() {
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(0, 0, 184, 30), Frame.NORMAL, true);

        WindowStateService.RestorePlan plan =
                WindowStateService.planRestore(saved, List.of(new Rectangle(0, 0, 200, 150)), NO_MIN);

        assertEquals(new Rectangle(0, 0, 200, 150), plan.bounds());
    }

    @Test
    void planRestoreStillAppliesMaximizedStateWithoutRestoreBounds() {
        // A window always closed maximized has zero bounds; gating on hasBounds would lose the maximized state.
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(0, 0, 1920, 1080), Frame.MAXIMIZED_BOTH, true);

        WindowStateService.RestorePlan plan = WindowStateService.planRestore(saved, List.of(PRIMARY), NO_MIN);

        assertNull(plan.bounds(), "without trusted restore bounds the size stays at its built-in default");
        assertEquals(Frame.MAXIMIZED_BOTH, plan.extendedState(), "but the maximized state must still be applied");
    }

    @Test
    void planRestoreStillAppliesMaximizedStateWhenBoundsAreUnplaceable() {
        // Same trap via a removed second screen: sanitize returns null, so skip setBounds but keep the state.
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(3000, 2000, 800, 600), Frame.NORMAL, true);
        saved.extendedState = Frame.MAXIMIZED_BOTH;

        WindowStateService.RestorePlan plan = WindowStateService.planRestore(saved, List.of(PRIMARY), NO_MIN);

        assertNull(plan.bounds(), "bounds that fit no screen are not applied");
        assertEquals(Frame.MAXIMIZED_BOTH, plan.extendedState(), "the maximized state must still be applied");
    }

    @Test
    void planRestoreStripsIconifiedBitSoWindowDoesNotReopenMinimized() {
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(100, 100, 800, 600), Frame.NORMAL, true);
        saved.extendedState = Frame.ICONIFIED;

        WindowStateService.RestorePlan plan = WindowStateService.planRestore(saved, List.of(PRIMARY), NO_MIN);

        assertEquals(new Rectangle(100, 100, 800, 600), plan.bounds());
        assertEquals(Frame.NORMAL, plan.extendedState(), "the window must not reopen minimized");
    }

    @Test
    void planRestoreStripsIconifiedBitFromMaximizedCombination() {
        // Saved XML may hold MAXIMIZED_BOTH|ICONIFIED (7); a != ICONIFIED check misses it and reopens minimized.
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(100, 100, 800, 600), Frame.NORMAL, true);
        saved.extendedState = Frame.MAXIMIZED_BOTH | Frame.ICONIFIED;

        WindowStateService.RestorePlan plan = WindowStateService.planRestore(saved, List.of(PRIMARY), NO_MIN);

        assertEquals(Frame.MAXIMIZED_BOTH, plan.extendedState(), "minus the minimized bit, it reopens maximized");
    }

    @Test
    void maximizedThenMinimizedRoundTripsBackToMaximized() {
        // Full capture-restore round trip: the stored 7 (MAXIMIZED_BOTH|ICONIFIED) must come back as 6.
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(10, 20, 800, 600), Frame.NORMAL, true);
        WindowStateService.applyCapture(saved, new Rectangle(0, 0, 1920, 1080), Frame.MAXIMIZED_BOTH, true);
        WindowStateService.applyCapture(saved, new Rectangle(-32000, -32000, 160, 28),
                Frame.MAXIMIZED_BOTH | Frame.ICONIFIED, true);

        WindowStateService.RestorePlan plan = WindowStateService.planRestore(saved, List.of(PRIMARY), NO_MIN);

        assertEquals(Frame.MAXIMIZED_BOTH, plan.extendedState(), "a minimized maximized window reopens maximized");
        assertEquals(new Rectangle(10, 20, 800, 600), plan.bounds(), "max/min captures must not taint the bounds");
    }

    @Test
    void planRestoreAppliesNothingForUnknownKey() {
        WindowStateService.RestorePlan plan = WindowStateService.planRestore(null, List.of(PRIMARY), NO_MIN);

        assertNull(plan.bounds());
        assertNull(plan.extendedState());
    }

    @Test
    void restoresMaximizedStateForRecordWithoutRestoreBounds() {
        // Display-gated end to end: a real JFrame covers the wiring in restoreFrameState that planRestore tests miss.
        assumeFalse(GraphicsEnvironment.isHeadless());
        WindowStateService service = new WindowStateService();
        WindowStateService.StateData data = new WindowStateService.StateData();
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        saved.key = "k";
        WindowStateService.applyCapture(saved, new Rectangle(0, 0, 1920, 1080), Frame.MAXIMIZED_BOTH, true);
        data.frames.add(saved);
        service.loadState(data);

        JFrame frame = new JFrame();
        frame.setSize(1200, 800);
        try {
            service.restoreFrameState("k", frame);

            assertEquals(Frame.MAXIMIZED_BOTH, frame.getExtendedState(), "always-maximized windows reopen maximized");
        } finally {
            frame.dispose();
        }
    }
    @Test
    void captureStoresIconifiedStateSoRestoreCanStripItBackToMaximized() {
        // A caller that captures only on close sees one ICONIFIED capture; storing it lets restore recover maximized.
        WindowStateService.FrameState saved = new WindowStateService.FrameState();

        WindowStateService.applyCapture(saved, new Rectangle(-32000, -32000, 160, 28),
                Frame.MAXIMIZED_BOTH | Frame.ICONIFIED, true);

        assertEquals(Frame.MAXIMIZED_BOTH | Frame.ICONIFIED, saved.extendedState);
        assertEquals(Frame.MAXIMIZED_BOTH,
                WindowStateService.planRestore(saved, List.of(PRIMARY), NO_MIN).extendedState(),
                "restoring should strip the bit back to maximized");
    }

    @Test
    void captureNeverStoresSentinelBoundsOfIconifiedFrame() {
        // Sentinels like -32000 stay out through the == NORMAL check: any value with the ICONIFIED bit is nonzero.
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        WindowStateService.applyCapture(saved, new Rectangle(10, 20, 800, 600), Frame.NORMAL, true);

        WindowStateService.applyCapture(saved, new Rectangle(-32000, -32000, 160, 28), Frame.ICONIFIED, true);

        assertEquals(new Rectangle(10, 20, 800, 600), boundsOf(saved), "minimized sentinels must not taint the bounds");
    }

    @Test
    void restoresBoundsForRecordWithTrustedBounds() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        WindowStateService service = new WindowStateService();
        WindowStateService.StateData data = new WindowStateService.StateData();
        WindowStateService.FrameState saved = new WindowStateService.FrameState();
        saved.key = "k";
        // Keep it small: restoreFrameState clamps to the real work area, and 400x300 fits on any display.
        WindowStateService.applyCapture(saved, new Rectangle(60, 40, 400, 300), Frame.NORMAL, true);
        data.frames.add(saved);
        service.loadState(data);

        JFrame frame = new JFrame();
        frame.setSize(1200, 800);
        try {
            service.restoreFrameState("k", frame);

            assertEquals(new Rectangle(60, 40, 400, 300), frame.getBounds());
        } finally {
            frame.dispose();
        }
    }
}
