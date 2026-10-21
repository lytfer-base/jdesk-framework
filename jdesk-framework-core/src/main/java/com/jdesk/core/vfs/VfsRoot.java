package com.jdesk.core.vfs;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable snapshot of one mounted root in a scoped VFS catalog.
 *
 * <p>The root path is normalized so path comparisons are stable inside one
 * platform. The first version only models local filesystem roots.
 */
public record VfsRoot(
        VfsRootId rootId,
        String name,
        Path rootPath
) {

    /**
     * Validates the root identity and normalizes its local path.
     */
    public VfsRoot {
        rootId = Objects.requireNonNull(rootId, "rootId");
        name = VfsFileId.requireId("root name", name);
        rootPath = Objects.requireNonNull(rootPath, "rootPath").toAbsolutePath().normalize();
    }
}
