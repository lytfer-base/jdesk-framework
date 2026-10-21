package com.jdesk.core.vfs;

import java.util.EventListener;

/** Listener for VFS events, registered with {@link VirtualFileManager#addVfsListener(VfsListener)}. */
@FunctionalInterface
public interface VfsListener extends EventListener {

    /** Called synchronously on the thread that fires each VFS event; don't do slow work here. */
    void fileChanged(VfsEvent event);
}
