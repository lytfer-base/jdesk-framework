package com.jdesk.core.vfs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Virtual file system for the {@code file} protocol that wraps local {@link Path}s as {@link VirtualFile}s through
 * {@code java.nio.file}. It caches no attributes, so refreshing does nothing.
 */
public class LocalFileSystem extends VirtualFileSystem {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileSystem.class);

    public static final String PROTOCOL = "file";

    public LocalFileSystem() {
        super(PROTOCOL);
    }

    @Override
    public VirtualFile findFileByPath(String path) {
        if (path == null || path.isEmpty()) return null;
        Path nioPath = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.exists(nioPath)) {
            return null;
        }
        return new LocalVirtualFile(nioPath, this);
    }

    @Override
    public void refresh(boolean async) {
        // Attributes are read on demand; there is no cache to invalidate.
    }

    // ============== Convenience ==============

    /** Wraps a local {@link Path} as a {@link VirtualFile}. */
    public VirtualFile wrap(Path path) {
        return new LocalVirtualFile(path.toAbsolutePath().normalize(), this);
    }

    /** Returns the local root directories, such as the Windows drives or the Unix root. */
    public VirtualFile[] getRoots() {
        List<VirtualFile> roots = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            roots.add(new LocalVirtualFile(root, this));
        }
        return roots.toArray(new VirtualFile[0]);
    }
}

/** Local {@link VirtualFile} backed by a {@link Path}. */
class LocalVirtualFile extends VirtualFile {

    private final Path path;
    private final LocalFileSystem fileSystem;

    LocalVirtualFile(Path path, LocalFileSystem fileSystem) {
        this.path = path;
        this.fileSystem = fileSystem;
    }

    /** Returns the underlying NIO path. */
    public Path getNioPath() {
        return path;
    }

    // ---- Identity ----

    @Override
    public String getName() {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }

    @Override
    public String getPath() {
        return path.toString();
    }

    @Override
    public String getUrl() {
        return "file://" + path.toString();
    }

    // ---- Hierarchy ----

    @Override
    public VirtualFile getParent() {
        Path parent = path.getParent();
        return parent != null ? new LocalVirtualFile(parent, fileSystem) : null;
    }

    @Override
    public boolean isDirectory() {
        return Files.isDirectory(path);
    }

    @Override
    public boolean exists() {
        return Files.exists(path);
    }

    @Override
    public VirtualFile[] getChildren() {
        if (!isDirectory()) return VirtualFile.EMPTY_ARRAY;
        List<VirtualFile> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path child : stream) {
                children.add(new LocalVirtualFile(child, fileSystem));
            }
        } catch (IOException e) {
            // On an I/O error such as missing permission, return the children read so far.
        }
        children.sort((a, b) -> {
            boolean aDir = a.isDirectory();
            boolean bDir = b.isDirectory();
            if (aDir != bDir) return aDir ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return children.toArray(new VirtualFile[0]);
    }

    // ---- Attributes ----

    @Override
    public long getLength() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public long getTimeStamp() {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public boolean isWritable() {
        return Files.isWritable(path);
    }

    @Override
    public boolean isValid() {
        return Files.exists(path);
    }

    // ---- I/O ----

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return Files.newOutputStream(path);
    }

    // ---- File system ----

    @Override
    public VirtualFileSystem getFileSystem() {
        return fileSystem;
    }

    // ---- Refresh ----

    @Override
    public void refresh(boolean async, boolean recursive) {
        // Attributes and children are always read live.
    }
}
