package com.jdesk.frame.window;

import com.jdesk.core.disposer.Disposable;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Lightweight tracker of application windows and their last focused components that suggests a reliable parent for
 * dialogs and helper windows; much smaller than IntelliJ's WindowManager and WindowWatcher. Windows are held weakly,
 * so closed ones can be collected.
 */
public class JdfWindowWatcher implements Disposable, PropertyChangeListener {

    private final KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
    private final Set<Window> knownWindows = Collections.newSetFromMap(new WeakHashMap<>());
    /** Windows never suggested as a dialog parent, such as transient popups or special tool windows. */
    private final Set<Window> notSuggestedAsParent = Collections.newSetFromMap(new WeakHashMap<>());
    /** Last focused component per window, kept after focus leaves the window. */
    private final Map<Window, Component> lastFocusedComponents = new WeakHashMap<>();
    private final WindowAdapter cleanupListener = new WindowAdapter() {
        @Override
        public void windowClosed(WindowEvent e) {
            forget(e.getWindow());
        }

        @Override
        public void windowClosing(WindowEvent e) {
            remember(e.getWindow());
        }

        @Override
        public void windowActivated(WindowEvent e) {
            remember(e.getWindow());
        }
    };

    /** Last usable window that held focus. */
    private volatile Window mostRecentFocusedWindow;

    public JdfWindowWatcher() {
        focusManager.addPropertyChangeListener("focusedWindow", this);
        focusManager.addPropertyChangeListener("focusOwner", this);
    }

    /** Starts tracking a window; watching it again is harmless, and it is dropped once closed. */
    public void watch(Window window) {
        if (window == null) {
            return;
        }
        synchronized (knownWindows) {
            if (knownWindows.add(window)) {
                window.addWindowListener(cleanupListener);
            }
        }
        remember(window);
    }

    /** Returns the last focused window that is still showing, or {@code null}. */
    public Window getMostRecentFocusedWindow() {
        Window window = mostRecentFocusedWindow;
        return isUsable(window) ? window : null;
    }

    /** Returns the focused component of {@link #getMostRecentFocusedWindow()}, or {@code null}. */
    public Component getMostRecentFocusedComponent() {
        Window window = getMostRecentFocusedWindow();
        return window == null ? null : getFocusedComponent(window);
    }

    /** Returns the window's focus owner if focus is inside it, otherwise its last focused component. */
    public Component getFocusedComponent(Window window) {
        if (window == null) {
            return null;
        }
        Component currentFocusOwner = focusManager.getFocusOwner();
        if (currentFocusOwner != null && SwingUtilities.getWindowAncestor(currentFocusOwner) == window) {
            return currentFocusOwner;
        }
        synchronized (lastFocusedComponents) {
            return lastFocusedComponents.get(window);
        }
    }

    /** Suggests a dialog parent: the last focused window, or another usable tracked window if that one is excluded. */
    public Window suggestParentWindow() {
        Window focused = getMostRecentFocusedWindow();
        if (focused != null && !isNotSuggestedAsParent(focused)) {
            return focused;
        }

        synchronized (knownWindows) {
            for (Window window : knownWindows) {
                if (isUsable(window) && !isNotSuggestedAsParent(window)) {
                    return window;
                }
            }
        }
        return null;
    }

    /** Excludes a window from parent suggestions while still tracking its focus. */
    public void doNotSuggestAsParent(Window window) {
        if (window != null) {
            synchronized (notSuggestedAsParent) {
                notSuggestedAsParent.add(window);
            }
        }
    }

    /** Returns whether a window is excluded from parent suggestions. */
    public boolean isNotSuggestedAsParent(Window window) {
        synchronized (notSuggestedAsParent) {
            return notSuggestedAsParent.contains(window);
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        if ("focusedWindow".equals(event.getPropertyName()) && event.getNewValue() instanceof Window window) {
            watch(window);
            return;
        }
        if ("focusOwner".equals(event.getPropertyName()) && event.getNewValue() instanceof Component component) {
            Window window = SwingUtilities.getWindowAncestor(component);
            if (window != null) {
                watch(window);
                synchronized (lastFocusedComponents) {
                    lastFocusedComponents.put(window, component);
                }
            }
        }
    }

    @Override
    public void dispose() {
        focusManager.removePropertyChangeListener("focusedWindow", this);
        focusManager.removePropertyChangeListener("focusOwner", this);
        synchronized (knownWindows) {
            for (Window window : knownWindows) {
                window.removeWindowListener(cleanupListener);
            }
            knownWindows.clear();
        }
        synchronized (lastFocusedComponents) {
            lastFocusedComponents.clear();
        }
        synchronized (notSuggestedAsParent) {
            notSuggestedAsParent.clear();
        }
        mostRecentFocusedWindow = null;
    }

    private void remember(Window window) {
        if (!isUsable(window)) {
            return;
        }
        mostRecentFocusedWindow = window;
        if (window instanceof Frame frame) {
            JOptionPane.setRootFrame(frame);
        }
    }

    private void forget(Window window) {
        synchronized (knownWindows) {
            knownWindows.remove(window);
        }
        synchronized (lastFocusedComponents) {
            lastFocusedComponents.remove(window);
        }
        synchronized (notSuggestedAsParent) {
            notSuggestedAsParent.remove(window);
        }
        if (mostRecentFocusedWindow == window) {
            mostRecentFocusedWindow = null;
        }
    }

    private static boolean isUsable(Window window) {
        return window != null && window.isDisplayable() && window.isShowing();
    }
}
