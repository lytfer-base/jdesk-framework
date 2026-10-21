package com.jdesk.frame.ui.components;

/**
 * Theme refresh for lightweight frame components: on a theme switch they read the JDesk colors and fonts again
 * instead of the component tree being rebuilt.
 */
public interface JdfThemedComponent {

    /** Reapplies colors, fonts, borders and the like from the current theme. */
    void updateJdfTheme();
}
