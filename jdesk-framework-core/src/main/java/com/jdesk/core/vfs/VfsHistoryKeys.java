package com.jdesk.core.vfs;

import com.jdesk.core.history.HistoryKey;

/**
 * Keys of managed files in local history: the kind is always {@value #FILE_KIND} and the ID is the {@link VfsFileId}.
 * Parse keys with the same constant.
 */
public final class VfsHistoryKeys {

    public static final String FILE_KIND = "file";

    private VfsHistoryKeys() {
    }

    public static HistoryKey of(VfsFileId fileId) {
        return HistoryKey.of(FILE_KIND, fileId.value());
    }
}
