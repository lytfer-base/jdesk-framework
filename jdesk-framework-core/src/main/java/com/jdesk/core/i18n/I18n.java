package com.jdesk.core.i18n;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.message.MessageBus;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Process-wide UI language and message lookup. The language is zh-CN until initialized, in tests too, and a switch
 * publishes {@link LocaleListener} on the message bus.
 */
public final class I18n {
    private static final MessageCatalog CATALOG = new MessageCatalog();
    private static final Locale ORIGINAL_LOCALE = Locale.getDefault();
    private static final Locale ORIGINAL_DISPLAY_LOCALE = Locale.getDefault(Locale.Category.DISPLAY);
    private static final Locale ORIGINAL_FORMAT_LOCALE = Locale.getDefault(Locale.Category.FORMAT);
    private static volatile UiLanguage current = UiLanguage.ZH_CN;

    private I18n() {
    }

    public static UiLanguage current() {
        return current;
    }

    /** Sets the language at startup without publishing an event, and sets the JVM default locale to match. */
    public static synchronized void initialize(UiLanguage language) {
        current = Objects.requireNonNull(language, "language");
        Locale.setDefault(language.locale());
    }

    public static void registerRoot(String resourceDir, ClassLoader loader, UiLanguage source) {
        CATALOG.registerRoot(resourceDir, loader, source);
    }

    public static String message(String key) {
        return message(key, Map.of());
    }

    /** Formats the message with parameters passed as alternating names and values. */
    public static String message(String key, Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Parameters must come in name/value pairs: " + key);
        }
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            params.put(String.valueOf(nameValuePairs[i]), nameValuePairs[i + 1]);
        }
        return message(key, params);
    }

    /** Formats the message in the current language; returns the key itself if it isn't found. */
    public static String message(String key, Map<String, ?> params) {
        String pattern = CATALOG.find(key, current);
        return pattern == null ? key : MessageFormatter.format(pattern, params);
    }

    public static Optional<String> find(String key) {
        return find(key, current);
    }

    /**
     * Returns the message in {@code language} without parameters, with the fallbacks of {@link #message(String)};
     * empty if the key isn't found.
     */
    public static Optional<String> find(String key, UiLanguage language) {
        return find(key, language, Map.of());
    }

    /**
     * Returns the message in {@code language} with parameters, choosing plural forms by count in that language,
     * without switching the current language; empty if the key isn't found.
     */
    public static Optional<String> find(String key, UiLanguage language, Map<String, ?> params) {
        return Optional.ofNullable(CATALOG.find(key, Objects.requireNonNull(language, "language")))
                .map(pattern -> MessageFormatter.format(pattern, params));
    }

    /** Switches the language at runtime, notifying subscribers synchronously on the calling thread. */
    public static synchronized void switchTo(UiLanguage language) {
        UiLanguage old = current;
        if (old == Objects.requireNonNull(language, "language")) {
            return;
        }
        initialize(language);
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app == null || app.isDisposed()) {
            return;
        }
        MessageBus bus = app.getMessageBus();
        if (bus != null && !bus.isDisposed()) {
            bus.syncPublisher(LocaleListener.class).localeChanged(old, language);
        }
    }

    /** Resets to the uninitialized state: clears the message roots and restores the JVM default locales. */
    public static synchronized void resetForTests() {
        current = UiLanguage.ZH_CN;
        CATALOG.clear();
        Locale.setDefault(ORIGINAL_LOCALE);
        Locale.setDefault(Locale.Category.DISPLAY, ORIGINAL_DISPLAY_LOCALE);
        Locale.setDefault(Locale.Category.FORMAT, ORIGINAL_FORMAT_LOCALE);
    }
}
