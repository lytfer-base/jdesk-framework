package com.jdesk.frame.state;

import com.jdesk.core.config.PersistentStateComponent;
import com.jdesk.core.config.State;
import com.jdesk.core.config.Storage;

import javax.swing.JFrame;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

/**
 * Application service that persists Swing window bounds, maximized state and visibility under a caller-chosen key.
 * Reads and writes are skipped in a headless environment.
 */
@State(name = "WindowStateService", storages = @Storage("window-state.xml"))
public final class WindowStateService implements PersistentStateComponent<WindowStateService.StateData> {

    /** Guarded by this object's lock; the store gets a snapshot from {@link #getState()}. */
    private StateData state = new StateData();

    /**
     * Applies the state saved under {@code key} to {@code frame}, as decided by {@link #planRestore}.
     * Call it only while constructing the window: on a window in use it would undo what the user has done since.
     */
    public void restoreFrameState(String key, JFrame frame) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        List<Rectangle> workAreas = currentWorkAreas();
        Dimension minimum = declaredMinimumSize(frame);
        RestorePlan plan;
        synchronized (this) {
            plan = planRestore(find(key), workAreas, minimum);
        }
        // Must set the bounds before maximizing: on Windows a later setBounds would overwrite the restore size.
        if (plan.bounds() != null) {
            frame.setBounds(plan.bounds());
        }
        if (plan.extendedState() != null) {
            frame.setExtendedState(plan.extendedState());
        }
    }

    /**
     * What to restore; a {@code null} part leaves the window as it is. Apply the bounds before the extended state.
     *
     * @param bounds        bounds to apply; {@code null} keeps the caller's default size and the platform position
     * @param extendedState extended state to apply; {@code null}, only when nothing was saved, leaves it as is
     */
    record RestorePlan(Rectangle bounds, Integer extendedState) {
    }

    /**
     * Decides what to restore from the saved state and the current screens; package-private so tests can call it
     * without a display. Bounds and extended state are independent: when the bounds are missing (see
     * {@link #applyCapture}) or fit no screen, the extended state is still restored.
     */
    static RestorePlan planRestore(FrameState saved, List<Rectangle> workAreas, Dimension min) {
        if (saved == null) {
            return new RestorePlan(null, null);
        }

        Rectangle bounds = saved.hasBounds()
                ? WindowBoundsSanitizer.sanitize(
                        new Rectangle(saved.x, saved.y, saved.width, saved.height), workAreas,
                        atLeast(min, MIN_RESTORED_SIZE))
                : null;
        // Clear the iconified bit, don't drop the state: a minimized maximized window saves MAXIMIZED_BOTH|ICONIFIED.
        return new RestorePlan(bounds, saved.extendedState & ~Frame.ICONIFIED);
    }

    /**
     * Floor for restored sizes, so a degenerate saved size (a bare title bar) isn't restored as is. It is smaller
     * than any real window; on a smaller screen the screen limit in {@link WindowBoundsSanitizer} still wins.
     */
    private static final Dimension MIN_RESTORED_SIZE = new Dimension(320, 240);

    /** Takes the larger minimum on each axis; a null {@code declared} yields the floor. */
    private static Dimension atLeast(Dimension declared, Dimension floor) {
        if (declared == null) {
            return floor;
        }
        return new Dimension(Math.max(declared.width, floor.width), Math.max(declared.height, floor.height));
    }

    /**
     * Returns the minimum size only if the window declared one; {@link #MIN_RESTORED_SIZE} covers the rest.
     * Don't use {@code getMinimumSize()} alone: without {@code setMinimumSize} it returns a layout-derived size that
     * Swing doesn't enforce, which would silently enlarge a small saved window.
     */
    private static Dimension declaredMinimumSize(JFrame frame) {
        return frame.isMinimumSizeSet() ? frame.getMinimumSize() : new Dimension(0, 0);
    }

    /**
     * Returns the work area of every screen, without the taskbar and other system areas; not for headless use.
     * Don't switch to {@code getBounds()}: with a top taskbar, the title bar would end up under it, out of reach.
     */
    private static List<Rectangle> currentWorkAreas() {
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        List<Rectangle> areas = new ArrayList<>(devices.length);
        for (GraphicsDevice device : devices) {
            GraphicsConfiguration config = device.getDefaultConfiguration();
            Rectangle area = config.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
            area.x += insets.left;
            area.y += insets.top;
            area.width -= insets.left + insets.right;
            area.height -= insets.top + insets.bottom;
            areas.add(area);
        }
        return areas;
    }

    /**
     * Saves the frame's bounds and extended state under {@code key}, replacing the previous entry. Safe to call at
     * any time and as often as needed: {@link #applyCapture} keeps only trustworthy bounds.
     */
    public void storeFrameState(String key, JFrame frame) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        Rectangle bounds = frame.getBounds();
        int extendedState = frame.getExtendedState();
        boolean visible = frame.isVisible();
        synchronized (this) {
            applyCapture(findOrCreate(key), bounds, extendedState, visible);
        }
    }

    /**
     * Merges one observation into {@code saved}; package-private for tests. Bounds count only from a NORMAL frame,
     * since maximized and minimized frames report full-screen or sentinel bounds.
     * Don't skip minimized observations: a caller that captures only on close would lose the maximized state.
     */
    static void applyCapture(FrameState saved, Rectangle bounds, int extendedState, boolean visible) {
        saved.visible = visible;
        saved.extendedState = extendedState;
        // NORMAL is zero, so == means no state bit is set; unlike a bit test it also rejects unknown future bits.
        if (extendedState == Frame.NORMAL) {
            saved.x = bounds.x;
            saved.y = bounds.y;
            saved.width = bounds.width;
            saved.height = bounds.height;
        }
    }

    /** Returns a snapshot: saving may serialize on another thread while a window capture changes the live state. */
    @Override
    public synchronized StateData getState() {
        StateData snapshot = new StateData();
        for (FrameState frame : state.frames) {
            snapshot.frames.add(frame.copy());
        }
        return snapshot;
    }

    @Override
    public synchronized void loadState(StateData state) {
        this.state = state == null ? new StateData() : state;
    }

    @Override
    public StateData getDefaultState() {
        return new StateData();
    }

    private FrameState find(String key) {
        for (FrameState frame : state.frames) {
            if (key.equals(frame.key)) {
                return frame;
            }
        }
        return null;
    }

    private FrameState findOrCreate(String key) {
        FrameState existing = find(key);
        if (existing != null) {
            return existing;
        }

        FrameState created = new FrameState();
        created.key = key;
        state.frames.add(created);
        return created;
    }

    /** Persisted root object, holding window states by key. */
    public static final class StateData {
        public List<FrameState> frames = new ArrayList<>();
    }

    /** Persisted state of one window; the fields stay public for XML serialization. */
    public static final class FrameState {
        public String key;
        public int x;
        public int y;
        public int width;
        public int height;
        public int extendedState = Frame.NORMAL;
        public boolean visible;

        boolean hasBounds() {
            return width > 0 && height > 0;
        }

        FrameState copy() {
            FrameState copy = new FrameState();
            copy.key = key;
            copy.x = x;
            copy.y = y;
            copy.width = width;
            copy.height = height;
            copy.extendedState = extendedState;
            copy.visible = visible;
            return copy;
        }
    }
}
