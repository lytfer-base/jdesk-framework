package com.jdesk.frame.vfs;

import com.jdesk.core.vfs.ScanFilter;
import com.jdesk.core.vfs.VfsWatchListener;
import com.jdesk.core.vfs.VfsWatcher;
import com.jdesk.core.vfs.VfsWatcherFactory;

/** Production {@link VfsWatcherFactory}, creating the global {@link DirectoryWatcherVfsWatcher}. */
public final class DirectoryWatcherVfsWatcherFactory implements VfsWatcherFactory {
    @Override
    public VfsWatcher create(ScanFilter filter, VfsWatchListener listener) {
        return new DirectoryWatcherVfsWatcher(filter, listener);
    }
}
