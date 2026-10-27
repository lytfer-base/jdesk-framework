package com.jdesk.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PathDirectoryCreationTest {
    @TempDir Path run;

    @Test
    void anAncestorRemovedAfterItWasObservedIsNotRecreated() throws Exception {
        Path existing = Files.createDirectory(run.resolve("existing"));
        Path moved = run.resolve("moved");
        AtomicBoolean displaced = new AtomicBoolean();
        var paths = new MovingPaths(existing, moved, displaced);
        PathAccessPolicy access = PathAccessPolicy.existingRoot(paths.wrap(run));
        assertThrows(IOException.class, () -> access.createDirectories(paths.wrap(existing.resolve("new/deep"))));
        assertTrue(displaced.get(), "the actual ancestor must move between observation and creation");
        assertFalse(Files.exists(existing));
        assertTrue(Files.isDirectory(moved));
    }

    @Test
    void nestedCreationUsesTheAmbientBoundaryAndRejectsFilesAsAncestors() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            PathAccessPolicy.createMissing().createDirectories(root.resolve("new/deep"));
            assertTrue(Files.isDirectory(root.resolve("new/deep")));
            assertThrows(IOException.class, () -> PathAccessPolicy.createMissing().createDirectories(run.resolve("outside")));
            Files.writeString(root.resolve("file"), "original");
            assertThrows(IOException.class, () -> PathAccessPolicy.current().createDirectories(root.resolve("file/child")));
            assertEquals("original", Files.readString(root.resolve("file")));
        }
        assertFalse(Files.exists(run.resolve("outside")));
    }

    @Test
    void createdCallbackReportsOnlyOurDirectoriesInCreationOrder() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        Path first = root.resolve("one");
        Path concurrent = first.resolve("two");
        Path last = concurrent.resolve("three");
        var created = new ArrayList<Path>();
        var other = Executors.newSingleThreadExecutor();
        try {
            PathAccessPolicy.existingRoot(root).createDirectories(last, directory -> {
                created.add(directory);
                if (directory.equals(first)) {
                    try {
                        other.submit(() -> {
                            Files.createDirectory(concurrent);
                            Files.writeString(concurrent.resolve("other-owner.txt"), "keep");
                            return null;
                        }).get(5, TimeUnit.SECONDS);
                    } catch (Exception failure) { throw new AssertionError(failure); }
                }
            });
            assertEquals(List.of(first, last), created);
            assertEquals("keep", Files.readString(concurrent.resolve("other-owner.txt")));
            assertTrue(Files.isDirectory(last));
        } finally { other.shutdownNow(); }
    }

    @Test
    void existingDirectoriesAndFailedCreatesAreNeverReportedAsOwned() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        var created = new ArrayList<Path>();
        var access = PathAccessPolicy.existingRoot(root);
        access.createDirectories(root, created::add);
        Files.writeString(root.resolve("file"), "original");
        assertThrows(IOException.class, () -> access.createDirectories(root.resolve("file/child"), created::add));
        assertTrue(created.isEmpty());
        assertEquals("original", Files.readString(root.resolve("file")));
    }

    @Test
    void aCallbackFailureRetainsAnExactRecordOfAlreadyCreatedDirectories() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        var created = new ArrayList<Path>();
        var failure = new IllegalStateException("stop after creation");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> PathAccessPolicy.existingRoot(root).createDirectories(root.resolve("one/two"), directory -> {
                    created.add(directory);
                    throw failure;
                })));
        assertEquals(List.of(root.resolve("one")), created);
        assertTrue(Files.isDirectory(root.resolve("one")));
        assertFalse(Files.exists(root.resolve("one/two")));
    }

    @Test
    void theDefaultCallbackOverloadReportsCreationWithoutChangingTheLegacyEntry() throws Exception {
        var created = new ArrayList<Path>();
        PathAccessPolicy.createMissing().createDirectories(run.resolve("new/deep"), created::add);
        assertEquals(List.of(run.resolve("new"), run.resolve("new/deep")), created);
        PathAccessPolicy.createMissing().createDirectories(run.resolve("legacy/deep"));
        assertTrue(Files.isDirectory(run.resolve("legacy/deep")));
    }

    /** Delegates to the real filesystem and moves one ancestor immediately after its successful attribute read. */
    private static final class MovingPaths extends FileSystemProvider {
        private final FileSystemProvider delegate = FileSystems.getDefault().provider();
        private final Path ancestor;
        private final Path moved;
        private final AtomicBoolean displaced;
        private final FileSystem filesystem = new FileSystem() {
            @Override public FileSystemProvider provider() { return MovingPaths.this; }
            @Override public void close() { }
            @Override public boolean isOpen() { return true; }
            @Override public boolean isReadOnly() { return false; }
            @Override public String getSeparator() { return FileSystems.getDefault().getSeparator(); }
            @Override public Iterable<Path> getRootDirectories() { return FileSystems.getDefault().getRootDirectories(); }
            @Override public Iterable<FileStore> getFileStores() { return FileSystems.getDefault().getFileStores(); }
            @Override public Set<String> supportedFileAttributeViews() { return FileSystems.getDefault().supportedFileAttributeViews(); }
            @Override public Path getPath(String first, String... more) { return wrap(Path.of(first, more)); }
            @Override public PathMatcher getPathMatcher(String syntax) { return FileSystems.getDefault().getPathMatcher(syntax); }
            @Override public UserPrincipalLookupService getUserPrincipalLookupService() { return FileSystems.getDefault().getUserPrincipalLookupService(); }
            @Override public WatchService newWatchService() throws IOException { return FileSystems.getDefault().newWatchService(); }
        };

        MovingPaths(Path ancestor, Path moved, AtomicBoolean displaced) {
            this.ancestor = ancestor;
            this.moved = moved;
            this.displaced = displaced;
        }

        Path wrap(Path path) {
            return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class}, (proxy, method, args) -> {
                if (method.getName().equals("getFileSystem")) return filesystem;
                Object[] actual = args == null ? null : args.clone();
                if (actual != null) for (int i = 0; i < actual.length; i++) {
                    if (actual[i] instanceof Path p) actual[i] = unwrap(p);
                }
                try {
                    Object result = method.invoke(path, actual);
                    return result instanceof Path p ? wrap(p) : result;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        }

        private Path unwrap(Path path) { return Path.of(path.toString()); }
        @Override public String getScheme() { return "moving-real-files"; }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { return filesystem; }
        @Override public FileSystem getFileSystem(URI uri) { return filesystem; }
        @Override public Path getPath(URI uri) { return wrap(Path.of(uri)); }
        @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            return delegate.newByteChannel(unwrap(path), options, attrs);
        }
        @Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
            return delegate.newDirectoryStream(unwrap(dir), filter);
        }
        @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException { delegate.createDirectory(unwrap(dir), attrs); }
        @Override public void delete(Path path) throws IOException { delegate.delete(unwrap(path)); }
        @Override public void copy(Path source, Path target, CopyOption... options) throws IOException { delegate.copy(unwrap(source), unwrap(target), options); }
        @Override public void move(Path source, Path target, CopyOption... options) throws IOException { delegate.move(unwrap(source), unwrap(target), options); }
        @Override public boolean isSameFile(Path a, Path b) throws IOException { return delegate.isSameFile(unwrap(a), unwrap(b)); }
        @Override public boolean isHidden(Path path) throws IOException { return delegate.isHidden(unwrap(path)); }
        @Override public FileStore getFileStore(Path path) throws IOException { return delegate.getFileStore(unwrap(path)); }
        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException { delegate.checkAccess(unwrap(path), modes); }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
            return delegate.getFileAttributeView(unwrap(path), type, options);
        }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
            Path actual = unwrap(path);
            A attributes = delegate.readAttributes(actual, type, options);
            if (actual.equals(ancestor) && displaced.compareAndSet(false, true)) Files.move(ancestor, moved);
            return attributes;
        }
        @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
            return delegate.readAttributes(unwrap(path), attributes, options);
        }
        @Override public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
            delegate.setAttribute(unwrap(path), attribute, value, options);
        }
    }
}
