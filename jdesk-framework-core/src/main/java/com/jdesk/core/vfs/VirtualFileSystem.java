package com.jdesk.core.vfs;

import com.jdesk.core.message.MessageBus;
import com.jdesk.core.message.MessageBusConnection;

/**
 * Base class of virtual file systems, each serving one protocol and one storage backend such as local or temporary
 * files. A file system may publish events through {@link VirtualFileManager} when files are created, deleted, moved
 * or changed.
 */
public abstract class VirtualFileSystem {

    private final String protocol;

    protected VirtualFileSystem(String protocol) {
        this.protocol = protocol;
    }

    /** Returns the protocol prefix used in URLs, such as {@code file}, {@code jar} or {@code temp}. */
    public String getProtocol() {
        return protocol;
    }

    /** Finds a file by its path within this file system. */
    public abstract VirtualFile findFileByPath(String path);

    /** Refreshes the state of the files this file system knows. */
    public abstract void refresh(boolean async);

    // ---- Events ----

    /** Publishes {@code event} to every VFS listener; subclasses call it when they detect a change. */
    protected void publishEvent(VfsEvent event) {
        VirtualFileManager.getInstance().fireEvent(event);
    }
}
