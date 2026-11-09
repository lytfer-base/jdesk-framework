package com.jdesk.frame.vfs;

import com.jdesk.core.vfs.VfsFileId;
import com.jdesk.core.vfs.VfsFileStatus;
import com.jdesk.core.vfs.VfsManagedFile;
import com.jdesk.core.vfs.VfsRootId;

import java.util.Collection;
import java.util.Optional;

/**
 * Local catalog operations on the caller's write transaction; each call requires its original thread and callback.
 * No method commits, opens a transaction, reads disk state, changes file bytes, or publishes events.
 */
public interface VfsCatalogTransaction {
    /** Reserves a MISSING row; identical retries do not reconcile disk, as in VfsCatalog.IdentityEditor. */
    VfsManagedFile registerFileWithId(VfsRootId rootId, String relativePath, boolean directory, VfsFileId fileId);

    /** Changes only the catalog ID; the caller updates dependent rows in this transaction. */
    VfsManagedFile rekeyFile(VfsFileId fileId, VfsFileId newFileId);

    /** Reads the row, including uncommitted changes on this transaction. */
    Optional<VfsManagedFile> findFile(VfsFileId fileId);

    /** Reads the row at the normalized path, including uncommitted changes. */
    Optional<VfsManagedFile> findFile(VfsRootId rootId, String relativePath);

    /** Sets lifecycle status and advances the row's content version. */
    VfsManagedFile updateStatus(VfsFileId fileId, VfsFileStatus status);

    /** Sets the logical flag without advancing content version; returns whether the row exists. */
    boolean setReadOnly(VfsFileId fileId, boolean readOnly);

    /** Removes the snapshot row; an absent ID is ignored. */
    void removeFile(VfsFileId fileId);

    /** Removes snapshot rows; absent IDs are ignored. */
    void removeFiles(Collection<VfsFileId> fileIds);

}
