package com.jdesk.frame.ui;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the headless parts of NativeFileDialogs: the filter memory layout and default extension derivation.
 * The COM dialog path needs a desktop and isn't covered here.
 */
class NativeFileDialogsTest {

    @Test
    void buildFilterSpecsLaysOutLabelPatternPairs() {
        List<NativeFileDialogs.FileTypeFilter> filters = List.of(
                new NativeFileDialogs.FileTypeFilter("图片文件", "*.jpg;*.png"),
                new NativeFileDialogs.FileTypeFilter("所有文件", "*.*"));
        List<Memory> keepAlive = new ArrayList<>();
        Memory specs = NativeFileDialogs.buildFilterSpecs(filters, keepAlive);

        // COMDLG_FILTERSPEC[2] is 4 wide-string pointers; read each one back.
        assertEquals(4, keepAlive.size());
        assertEquals("图片文件", specs.getPointer(0).getWideString(0));
        assertEquals("*.jpg;*.png", specs.getPointer(Native.POINTER_SIZE).getWideString(0));
        assertEquals("所有文件", specs.getPointer(2L * Native.POINTER_SIZE).getWideString(0));
        assertEquals("*.*", specs.getPointer(3L * Native.POINTER_SIZE).getWideString(0));
    }

    @Test
    void filterRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new NativeFileDialogs.FileTypeFilter(null, "*.*"));
        assertThrows(NullPointerException.class, () -> new NativeFileDialogs.FileTypeFilter("全部", null));
    }

    @Test
    void extensionOfDerivesLowercaseSuffixWithoutDot() {
        assertEquals("jpg", NativeFileDialogs.extensionOf("cover.JPG"));
        assertEquals("md", NativeFileDialogs.extensionOf("笔记.草稿.md"));
        assertEquals("", NativeFileDialogs.extensionOf("no-extension"));
        assertEquals("", NativeFileDialogs.extensionOf("trailingdot."));
        assertEquals("", NativeFileDialogs.extensionOf(".hidden")); // A dot-prefixed hidden name has no extension.
    }
}
