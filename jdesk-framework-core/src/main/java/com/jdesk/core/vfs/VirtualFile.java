package com.jdesk.core.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A file in the virtual file system, modeled on IntelliJ Platform's {@code VirtualFile}: a local file, an entry in an
 * archive or one in another storage backend, with a uniform API for hierarchy, basic attributes and streams.
 */
public abstract class VirtualFile {

    /** Shared empty array for files and empty directories. */
    public static final VirtualFile[] EMPTY_ARRAY = new VirtualFile[0];

    // ---- Identity ----

    /** Returns the simple name, such as {@code readme.txt}. */
    public abstract String getName();

    /** Returns the full path in the backing file system. */
    public abstract String getPath();

    /** Returns the extension, or an empty string if there is none. */
    public String getExtension() {
        String name = getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    /** Returns the name without its extension. */
    public String getNameWithoutExtension() {
        String name = getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Returns a URL-style string that identifies the file uniquely. */
    public abstract String getUrl();

    // ---- Hierarchy ----

    /** Returns the parent directory, or {@code null} for a root. */
    public abstract VirtualFile getParent();

    /** Returns whether this is a directory. */
    public abstract boolean isDirectory();

    /** Returns whether the file still exists in the backing storage. */
    public abstract boolean exists();

    /** Returns the children of a directory, or an empty array for a regular file. */
    public abstract VirtualFile[] getChildren();

    /** Returns the direct child named {@code name}, or {@code null} if there is none. */
    public VirtualFile findChild(String name) {
        for (VirtualFile child : getChildren()) {
            if (child.getName().equals(name)) return child;
        }
        return null;
    }

    // ---- Attributes ----

    /** Returns the size in bytes; usually 0 for a directory. */
    public abstract long getLength();

    /** Returns the last modified time in epoch milliseconds. */
    public abstract long getTimeStamp();

    /** Returns whether the file is writable. */
    public abstract boolean isWritable();

    /** Returns whether this object is still valid, that is neither deleted nor invalidated by a refresh. */
    public abstract boolean isValid();

    // ---- I/O ----

    /** Opens a stream that reads the content. */
    public abstract InputStream getInputStream() throws IOException;

    /** Opens a stream that writes the content; the implementation decides how existing content is replaced. */
    public abstract OutputStream getOutputStream() throws IOException;

    /** Reads the whole content. */
    public byte[] contentsToByteArray() throws IOException {
        try (InputStream in = getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** Returns the virtual file system this file belongs to. */
    public abstract VirtualFileSystem getFileSystem();

    // ---- Refresh ----

    /** Refreshes this node and its children from the backing storage; the default does nothing. */
    public void refresh(boolean async, boolean recursive) {
        // Subclasses that cache state override this.
    }

    // ---- Object methods ----

    @Override
    public String toString() {
        return getPath();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VirtualFile that)) return false;
        return getUrl().equals(that.getUrl());
    }

    @Override
    public int hashCode() {
        return getUrl().hashCode();
    }
}
