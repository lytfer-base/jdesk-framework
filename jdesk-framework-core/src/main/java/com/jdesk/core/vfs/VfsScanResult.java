package com.jdesk.core.vfs;

import java.util.List;
import java.util.Objects;

/**
 * Differences found by one {@link VfsCatalog#refresh}, classified by how rows enter or leave the PRESENT set: added
 * rows entered it (new, moved to a new path, or back from MISSING), updated rows stayed and changed content, and
 * missing rows left it. Don't count a row back from MISSING as updated: subscribers rely on added and missing to see
 * whether the set changed.
 */
public record VfsScanResult(
        VfsRootId rootId,
        List<VfsManagedFile> added,
        List<VfsManagedFile> updated,
        List<VfsManagedFile> missing,
        int unchanged
) {
    public VfsScanResult {
        rootId = Objects.requireNonNull(rootId, "rootId");
        added = List.copyOf(added);
        updated = List.copyOf(updated);
        missing = List.copyOf(missing);
    }

    /** Returns how many entries the reconciliation covered. */
    public int total() {
        return added.size() + updated.size() + missing.size() + unchanged;
    }
}
