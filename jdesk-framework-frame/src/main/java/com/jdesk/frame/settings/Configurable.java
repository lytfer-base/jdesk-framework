package com.jdesk.frame.settings;

import javax.swing.*;

/**
 * A page of the settings dialog, modeled on IntelliJ Platform's {@code Configurable}: it creates a Swing component
 * and reports whether it is modified, applies and resets. The dialog calls {@link #isModified()}, {@link #apply()}
 * and {@link #reset()} only on pages opened this time, and always after {@link #createComponent()}.
 */
public interface Configurable {

    /** Returns the name shown in the settings tree. */
    String getDisplayName();

    /** Returns an optional description shown at the top of the page. */
    default String getDescription() { return null; }

    /** Creates the page component; called every time the page is shown, and it must show the saved values. */
    JComponent createComponent();

    /** Returns whether the user changed the page since the last apply or reset. */
    boolean isModified();

    /** Saves the current values; called on OK or Apply. */
    void apply();

    /** Reverts to the last applied values; called on Reset, on "No" when switching pages, and on closing without OK. */
    void reset();

    /** Returns an optional icon for the tree node. */
    default Icon getIcon() { return null; }

    /** Returns whether the page can be opened. */
    default boolean isEnabled() { return true; }
}
