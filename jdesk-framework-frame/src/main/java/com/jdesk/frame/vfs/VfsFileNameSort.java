package com.jdesk.frame.vfs;

import java.text.Collator;
import java.util.Locale;

/**
 * Locale-aware file name sort keys: {@link Collator} keys stored as bytes in {@code file_name_sort}, whose byte order
 * matches the collator's, so SQLite's default BLOB comparison sorts names by locale.
 * Don't switch to a custom SQLite collation: every connection that hasn't registered it would fail to write.
 */
final class VfsFileNameSort {

    private VfsFileNameSort() {
    }

    /** Returns a default-strength (TERTIARY) collator for {@code locale}; null means {@link Locale#getDefault()}. */
    static Collator collatorFor(Locale locale) {
        return Collator.getInstance(locale == null ? Locale.getDefault() : locale);
    }

    /** Returns the sort key bytes of a file name; a null name counts as empty, so one odd row can't break a scan. */
    static byte[] key(Collator collator, String fileName) {
        return collator.getCollationKey(fileName == null ? "" : fileName).toByteArray();
    }
}
