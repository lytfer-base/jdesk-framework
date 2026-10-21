package com.jdesk.core.path;

import java.util.Locale;
import java.util.Objects;

/**
 * Path case semantics on Windows: case-insensitive, with {@code toLowerCase(Locale.ROOT)} as the canonical form. Get
 * the instance through {@link PathCaseSemantics#current()}.
 * Don't drop {@link Locale#ROOT}: a Turkish default locale folds {@code I} to a dotless i
 * (caseFoldingSurvivesTurkishDefaultLocale).
 */
final class WindowsPathCaseSemantics implements PathCaseSemantics {

    static final WindowsPathCaseSemantics INSTANCE = new WindowsPathCaseSemantics();

    private WindowsPathCaseSemantics() {
    }

    @Override
    public String canonicalKey(String path) {
        Objects.requireNonNull(path, "path");
        return path.toLowerCase(Locale.ROOT);
    }
}
