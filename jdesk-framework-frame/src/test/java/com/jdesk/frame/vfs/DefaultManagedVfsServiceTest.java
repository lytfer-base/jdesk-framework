package com.jdesk.frame.vfs;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.vfs.ScanFilter;
import com.jdesk.core.vfs.VfsCatalog;
import com.jdesk.core.vfs.VfsCatalogBackend;
import com.jdesk.core.vfs.VfsFileId;
import com.jdesk.core.vfs.VfsRootId;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.DatabaseService;
import com.jdesk.frame.storage.StorageException;
import com.jdesk.frame.storage.sqlite.SqliteDatabaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultManagedVfsServiceTest {

    @TempDir
    Path homeDir;

    @AfterEach
    void clearState() {
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void opensCatalogFromDatabaseBackend() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.service.open");

        try {
            DefaultManagedVfsService service = new DefaultManagedVfsService();

            VfsCatalog catalog = service.openCatalog("library-a", DatabaseVfsCatalogBackend.of(database));

            assertInstanceOf(SqliteVfsCatalog.class, catalog);
            assertTrue(service.findOpenCatalog("library-a").isPresent());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void openingSameScopeReturnsSameCatalog() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.service.same");

        try {
            DefaultManagedVfsService service = new DefaultManagedVfsService();

            VfsCatalog first = service.openCatalog("library-a", DatabaseVfsCatalogBackend.of(database));
            VfsCatalog second = service.openCatalog("library-a", DatabaseVfsCatalogBackend.of(database));

            assertSame(first, second);
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void closeCatalogRemovesAndClosesCatalog() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.service.close");

        try {
            DefaultManagedVfsService service = new DefaultManagedVfsService();
            VfsCatalog catalog = service.openCatalog("library-a", DatabaseVfsCatalogBackend.of(database));

            assertTrue(service.closeCatalog("library-a"));

            assertFalse(service.findOpenCatalog("library-a").isPresent());
            assertThrows(StorageException.class, catalog::listRoots);
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void rejectsUnsupportedBackend() {
        DefaultManagedVfsService service = new DefaultManagedVfsService();
        VfsCatalogBackend backend = () -> "unsupported";

        assertThrows(IllegalArgumentException.class, () -> service.openCatalog("library-a", backend));
    }

    /**
     * Reopening a scope after its database was disposed returns a fresh catalog, not a stale one holding a dead
     * handle. Unlike {@link #openingSameScopeReturnsSameCatalog()}, the database here is closed.
     */
    @Test
    void reopeningScopeAfterDatabaseDisposedReturnsFreshCatalog() {
        PathManager.setHomePathForTests(homeDir);
        DefaultManagedVfsService service = new DefaultManagedVfsService();

        DatabaseHandle first = openDatabase("vfs.service.stale");
        VfsCatalog stale = service.openCatalog("library-a", DatabaseVfsCatalogBackend.of(first));
        Disposer.dispose(first);

        assertFalse(service.findOpenCatalog("library-a").isPresent(),
                "after the database closes the scope must not report open; the stale catalog is unusable");

        DatabaseHandle second = openDatabase("vfs.service.stale");
        try {
            VfsCatalog fresh = service.openCatalog("library-a", DatabaseVfsCatalogBackend.of(second));

            assertNotSame(stale, fresh, "a closed database must lead to a fresh catalog, not the stale instance");
            assertDoesNotThrow(fresh::listRoots, "the fresh catalog should work; a stale one throws here");
        } finally {
            Disposer.dispose(second);
        }
    }

    /**
     * Opening a scope again with another locale reuses the open catalog instead of rebuilding it. Whether the locale
     * reaches the catalog is checked by {@link #openCatalogPassesLocaleDownToStoredSortKeys()}.
     */
    @Test
    void openCatalogWithDifferentLocaleReusesOpenCatalogInsteadOfRebuilding() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.locale");
        try {
            DefaultManagedVfsService service = new DefaultManagedVfsService();
            VfsCatalog first = service.openCatalog("scope-a", DatabaseVfsCatalogBackend.of(database), Locale.CHINA);
            VfsCatalog second = service.openCatalog("scope-a", DatabaseVfsCatalogBackend.of(database), Locale.US);
            assertSame(first, second, "the scope reuses its open catalog; a later locale has no effect");
            Disposer.dispose(service);
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void twoArgOpenCatalogStillWorks() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.locale.default");
        try {
            DefaultManagedVfsService service = new DefaultManagedVfsService();
            assertNotNull(service.openCatalog("scope-b", DatabaseVfsCatalogBackend.of(database)));
            Disposer.dispose(service);
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * The locale passed to {@code openCatalog} reaches the catalog: the same Chinese names scanned under zh-CN and
     * en-US get different {@code file_name_sort} keys in opposite orders (pinyin for zh-CN, code points for en-US).
     */
    @Test
    void openCatalogPassesLocaleDownToStoredSortKeys() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle chineseDatabase = openDatabase("vfs.locale.zh");
        DatabaseHandle englishDatabase = openDatabase("vfs.locale.en");
        DefaultManagedVfsService service = new DefaultManagedVfsService();
        try {
            byte[][] chinese = scanSortKeys(service, "scope-zh", chineseDatabase,
                    homeDir.resolve("zh-lib"), Locale.CHINA);
            byte[][] english = scanSortKeys(service, "scope-en", englishDatabase,
                    homeDir.resolve("en-lib"), Locale.US);

            assertNotEquals(0, Arrays.compareUnsigned(chinese[0], english[0]),
                    "the same file name must get different sort keys under the two locales");
            assertTrue(Arrays.compareUnsigned(chinese[0], chinese[1]) < 0,
                    "zh-CN sorts by pinyin: 阿里.md < 中国.md");
            assertTrue(Arrays.compareUnsigned(english[0], english[1]) > 0,
                    "en-US sorts by code point: 中国.md < 阿里.md");
            Disposer.dispose(service);
        } finally {
            Disposer.dispose(englishDatabase);
            Disposer.dispose(chineseDatabase);
        }
    }

    /** Scans two Chinese-named files under root with the given locale and returns their stored sort keys, in order. */
    private byte[][] scanSortKeys(DefaultManagedVfsService service, String scopeId, DatabaseHandle database,
                                  Path root, Locale locale) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("阿里.md"), "a");
        Files.writeString(root.resolve("中国.md"), "z");
        VfsCatalog catalog = service.openCatalog(scopeId, DatabaseVfsCatalogBackend.of(database), locale);
        VfsRootId rootId = catalog.registerRoot(scopeId, scopeId, root);
        catalog.scanRoot(rootId, ScanFilter.all());
        return new byte[][]{
                sortKey(database, catalog.findFile(rootId, "阿里.md").orElseThrow().fileId()),
                sortKey(database, catalog.findFile(rootId, "中国.md").orElseThrow().fileId())
        };
    }

    private static byte[] sortKey(DatabaseHandle database, VfsFileId fileId) {
        return database.read(session -> session.queryOne(
                "select file_name_sort as v from jdesk_vfs_files where file_id = ?",
                row -> row.getBytes("v"), fileId.value())).orElseThrow();
    }

    private DatabaseHandle openDatabase(String id) {
        DatabaseService service = new SqliteDatabaseService();
        return service.open(DatabaseDescriptor.builder(id)
                .owner(DatabaseOwner.application("tests"))
                .name(id)
                .build());
    }
}
