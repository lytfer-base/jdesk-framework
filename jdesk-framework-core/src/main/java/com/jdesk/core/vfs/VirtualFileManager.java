package com.jdesk.core.vfs;

import com.jdesk.core.app.Application;
import com.jdesk.util.text.StringUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry of the virtual file system: maps protocols to {@link VirtualFileSystem} implementations and
 * dispatches VFS events synchronously. A process-wide singleton that {@link com.jdesk.core.app.ApplicationCore}
 * registers as a core service.
 */
public class VirtualFileManager {

    private static final Logger LOG = LoggerFactory.getLogger(VirtualFileManager.class);

    private static final VirtualFileManager INSTANCE = new VirtualFileManager();

    private final Map<String, VirtualFileSystem> fileSystems = new ConcurrentHashMap<>();

    private final List<VfsListener> listeners = new CopyOnWriteArrayList<>();

    private Application application;

    private VirtualFileManager() {}

    /** Returns the process-wide instance. */
    public static VirtualFileManager getInstance() {
        return INSTANCE;
    }

    // ============== File systems ==============

    /** Registers the file system for {@code protocol} (for example {@code file}), replacing any previous one. */
    public void registerFileSystem(String protocol, VirtualFileSystem fileSystem) {
        VirtualFileSystem old = fileSystems.put(protocol, fileSystem);
        if (old != null && old != fileSystem) {
            LOG.warn("Replaced existing file system for protocol '{}'", protocol);
        }
        LOG.info("Registered file system '{}' for protocol '{}'", fileSystem.getClass().getSimpleName(), protocol);
    }

    /** Returns the file system for {@code protocol}, or {@code null} if none is registered. */
    public VirtualFileSystem getFileSystem(String protocol) {
        return fileSystems.get(protocol);
    }

    // ============== Resolution ==============

    /**
     * Finds a file by a {@code protocol://path} URL; a URL without a protocol goes to the local {@code file} system.
     * Returns {@code null} if the URL can't be resolved or the file doesn't exist.
     */
    public VirtualFile findFileByUrl(String url) {
        if (StringUtil.isEmpty(url)) return null;

        int idx = url.indexOf("://");
        if (idx < 0) {
            VirtualFileSystem localFs = getFileSystem("file");
            if (localFs != null) {
                return localFs.findFileByPath(url);
            }
            LOG.warn("Cannot resolve file without protocol: {}", url);
            return null;
        }

        String protocol = url.substring(0, idx);
        String path = url.substring(idx + 3);

        VirtualFileSystem fs = getFileSystem(protocol);
        if (fs == null) {
            LOG.warn("No file system for protocol '{}' (url: {})", protocol, url);
            return null;
        }

        return fs.findFileByPath(path);
    }

    // ============== Events ==============

    /** Dispatches {@code event} synchronously to every listener; one that throws is logged and the rest still run. */
    public void fireEvent(VfsEvent event) {
        if (listeners.isEmpty()) return;

        for (VfsListener listener : listeners) {
            try {
                listener.fileChanged(event);
            } catch (Exception e) {
                LOG.error("VFS listener threw exception for event {}", event, e);
            }
        }
    }

    /** Registers a listener, which is called synchronously on the thread that fires each event. */
    public void addVfsListener(VfsListener listener) {
        listeners.add(listener);
    }

    /** Removes a listener registered with {@link #addVfsListener}. */
    public void removeVfsListener(VfsListener listener) {
        listeners.remove(listener);
    }

    // ============== Refresh ==============

    /** Refreshes every registered file system. */
    public void refresh(boolean async) {
        for (VirtualFileSystem fs : fileSystems.values()) {
            try {
                fs.refresh(async);
            } catch (Exception e) {
                LOG.error("Error refreshing file system {}", fs.getProtocol(), e);
            }
        }
    }

    // ============== Application ==============

    /** Sets the application; called during framework initialization. */
    public void setApplication(Application app) {
        this.application = app;
    }

    /** Returns the application, or {@code null} before initialization. */
    public Application getApplication() {
        return application;
    }
}
