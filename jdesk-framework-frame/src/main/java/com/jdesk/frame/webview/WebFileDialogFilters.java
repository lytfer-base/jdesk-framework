package com.jdesk.frame.webview;

import com.jdesk.core.i18n.I18n;
import com.jdesk.frame.ui.NativeFileDialogs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Turns Chromium's extension lists into native file dialog filters, for any WebView backend. */
public final class WebFileDialogFilters {

    private WebFileDialogFilters() {
    }

    /**
     * Returns dialog filters for the extensions, normalizing forms like "jpg" and ".jpg"; adds "All files" when the
     * list is empty or {@code acceptAll} is set.
     */
    public static List<NativeFileDialogs.FileTypeFilter> filters(String description,
                                                                 List<String> extensions,
                                                                 boolean acceptAll) {
        List<NativeFileDialogs.FileTypeFilter> result = new ArrayList<>();
        Optional<String> pattern = toPattern(extensions);
        if (pattern.isPresent()) {
            String label = description == null || description.isBlank()
                    ? I18n.message("frame.fileDialog.supportedFiles") : description;
            result.add(new NativeFileDialogs.FileTypeFilter(label, pattern.orElseThrow()));
        }
        if (result.isEmpty() || acceptAll) {
            result.add(new NativeFileDialogs.FileTypeFilter(I18n.message("frame.fileDialog.allFiles"), "*.*"));
        }
        return result;
    }

    /** Turns ["jpg", ".png", "*.gif"] into "*.jpg;*.png;*.gif"; returns empty for a blank or empty list. */
    public static Optional<String> toPattern(List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        for (String raw : extensions) {
            String ext = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            while (ext.startsWith("*") || ext.startsWith(".")) {
                ext = ext.substring(1);
            }
            if (!ext.isEmpty()) {
                parts.add("*." + ext);
            }
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(";", parts));
    }
}
