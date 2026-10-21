package com.jdesk.frame.webview;

import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.UiLanguage;
import com.jdesk.frame.ui.NativeFileDialogs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests turning Chromium's extension lists into dialog filters; the dialog itself can't be tested headless. */
class WebFileDialogFiltersTest {

    @BeforeEach
    void registerFrameMessages() {
        I18n.resetForTests();
        I18n.registerRoot("jdesk/i18n", getClass().getClassLoader(), UiLanguage.EN);
    }

    @AfterEach
    void resetMessages() {
        I18n.resetForTests();
    }

    @Test
    void toPatternNormalizesDotAndStarPrefixes() {
        assertEquals(Optional.of("*.jpg;*.png;*.gif"),
                WebFileDialogFilters.toPattern(List.of("jpg", ".PNG", "*.gif")));
        assertTrue(WebFileDialogFilters.toPattern(List.of()).isEmpty());
        assertTrue(WebFileDialogFilters.toPattern(Arrays.asList("", "  ", null, "*.")).isEmpty());
    }

    @Test
    void filtersFallBackToAllFilesWhenEmptyAndAppendOnAcceptAll() {
        String allFiles = I18n.message("frame.fileDialog.allFiles");
        List<NativeFileDialogs.FileTypeFilter> empty = WebFileDialogFilters.filters("Images", List.of(), false);
        assertEquals(List.of(new NativeFileDialogs.FileTypeFilter(allFiles, "*.*")), empty);

        List<NativeFileDialogs.FileTypeFilter> typed = WebFileDialogFilters.filters("Images", List.of("jpg"), false);
        assertEquals(List.of(new NativeFileDialogs.FileTypeFilter("Images", "*.jpg")), typed);

        List<NativeFileDialogs.FileTypeFilter> acceptAll = WebFileDialogFilters.filters(" ", List.of("jpg"), true);
        assertEquals(List.of(
                new NativeFileDialogs.FileTypeFilter(I18n.message("frame.fileDialog.supportedFiles"), "*.jpg"),
                new NativeFileDialogs.FileTypeFilter(allFiles, "*.*")), acceptAll);
    }

    @Test
    void fallbackLabelsFollowUiLanguage() {
        I18n.initialize(UiLanguage.EN);
        assertEquals(List.of(
                new NativeFileDialogs.FileTypeFilter("Supported files", "*.jpg"),
                new NativeFileDialogs.FileTypeFilter("All files", "*.*")),
                WebFileDialogFilters.filters(null, List.of("jpg"), true));
    }
}
