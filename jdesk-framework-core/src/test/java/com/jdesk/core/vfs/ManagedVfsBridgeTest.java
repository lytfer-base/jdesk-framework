package com.jdesk.core.vfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedVfsBridgeTest {

    @TempDir
    Path dir;

    @Test
    void toVirtualFileReadsRealContent() throws Exception {
        Path file = dir.resolve("note.md");
        Files.writeString(file, "hello");
        VfsManagedFile managed = new VfsManagedFile(
                VfsFileId.of("f1"), VfsRootId.of("r1"), "note.md", file,
                VfsFileStatus.PRESENT, false, 5, 0, 1, 0);

        ManagedVfsBridge bridge = new ManagedVfsBridge(new RecordingCatalog(managed), new LocalFileSystem());
        VirtualFile vf = bridge.toVirtualFile(managed);

        assertEquals("note.md", vf.getName());
        assertEquals("hello", new String(vf.contentsToByteArray()));
    }

    @Test
    void toManagedFileDelegatesToCatalogByPath() {
        Path file = dir.resolve("note.md");
        VfsManagedFile managed = new VfsManagedFile(
                VfsFileId.of("f1"), VfsRootId.of("r1"), "note.md", file,
                VfsFileStatus.PRESENT, false, 0, 0, 1, 0);
        LocalFileSystem fs = new LocalFileSystem();
        ManagedVfsBridge bridge = new ManagedVfsBridge(new RecordingCatalog(managed), fs);

        Optional<VfsManagedFile> resolved = bridge.toManagedFile(fs.wrap(file));
        assertTrue(resolved.isPresent());
        assertEquals(managed.fileId(), resolved.get().fileId());
    }

    /** Minimal catalog implementing just what the bridge needs. */
    private static final class RecordingCatalog implements VfsCatalog {
        private final VfsManagedFile file;

        RecordingCatalog(VfsManagedFile file) {
            this.file = file;
        }

        @Override
        public Optional<VfsManagedFile> findFile(Path absolutePath) {
            return absolutePath.toAbsolutePath().normalize().equals(file.absolutePath())
                    ? Optional.of(file) : Optional.empty();
        }

        @Override public String scopeId() { return "test"; }
        @Override public VfsRootId registerRoot(String rootKey, String name, Path rootPath) { throw new UnsupportedOperationException(); }
        @Override public List<VfsRoot> listRoots() { return List.of(); }
        @Override public VfsManagedFile registerFile(VfsRootId rootId, String relativePath, boolean directory) { throw new UnsupportedOperationException(); }
        @Override public Optional<VfsManagedFile> findFile(VfsFileId fileId) { return Optional.empty(); }
        @Override public Optional<VfsManagedFile> findFile(VfsRootId rootId, String relativePath) { return Optional.empty(); }
        @Override public VfsManagedFile moveFile(VfsFileId fileId, VfsRootId newRootId, String newRelativePath) { throw new UnsupportedOperationException(); }
        @Override public VfsManagedFile updateStatus(VfsFileId fileId, VfsFileStatus status) { throw new UnsupportedOperationException(); }
        @Override public boolean setReadOnly(VfsFileId fileId, boolean readOnly) { throw new UnsupportedOperationException(); }

        @Override
        public void removeFile(VfsFileId fileId) {
            throw new UnsupportedOperationException("this test double doesn't cover removeFile");
        }

        @Override
        public void removeFiles(Collection<VfsFileId> fileIds) {
            throw new UnsupportedOperationException("this test double doesn't cover removeFiles");
        }

        @Override public VfsScanResult refresh(VfsRootId rootId, String relativePath, boolean recursive, ScanFilter filter) { return new VfsScanResult(rootId, List.of(), List.of(), List.of(), 0); }
        @Override public VfsManagedFile refreshFile(VfsFileId fileId) { return file; }
        @Override public List<VfsManagedFile> listChildren(VfsRootId rootId, String dirRelativePath) { return List.of(); }
        @Override public void close() { }
    }
}
