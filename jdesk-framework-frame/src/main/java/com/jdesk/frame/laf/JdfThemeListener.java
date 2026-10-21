package com.jdesk.frame.laf;

/** Message bus topic for runtime theme changes, published synchronously once the new theme is applied. */
@FunctionalInterface
public interface JdfThemeListener {

    void themeChanged(JdfTheme oldTheme, JdfTheme newTheme);
}
