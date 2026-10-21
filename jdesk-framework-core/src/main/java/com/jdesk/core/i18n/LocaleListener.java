package com.jdesk.core.i18n;

/**
 * Message bus topic for UI language switches, published synchronously on the switching thread; UI subscribers must
 * move to the EDT themselves. Callbacks run under the class lock held by {@link I18n#switchTo}, so don't wait for
 * other threads in them.
 */
@FunctionalInterface
public interface LocaleListener {
    void localeChanged(UiLanguage oldLanguage, UiLanguage newLanguage);
}
