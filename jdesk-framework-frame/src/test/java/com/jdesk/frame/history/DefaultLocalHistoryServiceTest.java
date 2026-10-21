package com.jdesk.frame.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jdesk.core.history.CaptureRequest;
import com.jdesk.core.history.ContentSource;
import com.jdesk.core.history.HistoryKey;
import com.jdesk.core.history.HistoryPolicy;
import com.jdesk.core.history.HistorySource;
import com.jdesk.core.history.HistoryUnavailableReason;
import com.jdesk.core.history.LocalHistoryStore;
import com.jdesk.frame.history.HistoryTestSupport.MutableClock;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseService;
import com.jdesk.frame.storage.StorageException;
import com.jdesk.frame.storage.sqlite.SqliteDatabaseService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class DefaultLocalHistoryServiceTest {

    private static final HistoryKey KEY = HistoryKey.of("file", "f1");

    @TempDir
    Path dir;

    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private MutableClock clock;
    private DefaultLocalHistoryService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        clock = new MutableClock(HistoryTestSupport.BASE);
        service = new DefaultLocalHistoryService(SqliteDatabaseService::new, executor, scheduler, clock);
    }

    @AfterEach
    void tearDown() {
        service.dispose();
    }

    private static CaptureRequest request(String text) {
        return CaptureRequest.of(KEY, "a.md", ContentSource.bytes(text.getBytes(StandardCharsets.UTF_8)), HistorySource.APP);
    }

    @Test
    void openWritesGitignoreAndSchema() throws Exception {
        Path historyDir = dir.resolve("proj").resolve(".myapp").resolve("history");
        LocalHistoryStore store = service.openStore("scope.a", historyDir, HistoryPolicy.defaults());
        assertEquals("*\n", Files.readString(historyDir.resolve(".gitignore")));
        assertTrue(Files.isRegularFile(historyDir.resolve("history.sqlite")));
        assertTrue(store.capture(request("v1")).join().captured());
        assertTrue(store.stats().available());
        assertTrue(service.findOpenStore("scope.a").isPresent());
        assertTrue(service.findOpenStore("scope.b").isEmpty());
        assertTrue(service.openStore("scope.a", historyDir, HistoryPolicy.defaults()) == store, "the store is reused");
    }

    @Test
    void closeSealsHeadsSoNextSessionStartsNewVersion() {
        Path historyDir = dir.resolve("history");
        LocalHistoryStore store = service.openStore("scope.a", historyDir, HistoryPolicy.defaults());
        store.capture(request("v1")).join();
        assertTrue(service.closeStore("scope.a"));
        assertFalse(service.closeStore("scope.a"));
        clock.advance(60_000);
        LocalHistoryStore reopened = service.openStore("scope.a", historyDir, HistoryPolicy.defaults());
        reopened.capture(request("v2")).join();
        assertEquals(2, reopened.versions(KEY).size(), "closing seals, so a write after reopening starts a new row");
    }

    /**
     * Closing a store waits for <b>its own</b> queued work, so closing right after deleting a large folder doesn't
     * drop the delete marks still in the queue.
     */
    @Test
    void closeStoreWaitsForItsOwnQueuedDeleteMarks() throws Exception {
        Path historyDir = dir.resolve("history");
        LocalHistoryStore store = service.openStore("scope.a", historyDir, HistoryPolicy.defaults());
        store.capture(request("v1")).join();
        CountDownLatch blocked = new CountDownLatch(1);
        executor.submit(() -> blocked.await(30, TimeUnit.SECONDS)); // Block the shared capture thread.
        store.markDeletedLater(KEY, clock.millis());               // Queued but not run yet.
        // Block past 2 seconds: a close that stops waiting early would leave the mark to hit a closed handle.
        CompletableFuture.delayedExecutor(2500, TimeUnit.MILLISECONDS).execute(blocked::countDown);

        CompletableFuture<Boolean> closing = CompletableFuture.supplyAsync(() -> service.closeStore("scope.a"));
        assertThrows(TimeoutException.class, () -> closing.get(300, TimeUnit.MILLISECONDS),
                "closing must not proceed while the store's own queued work is pending");

        assertTrue(closing.get(20, TimeUnit.SECONDS));

        LocalHistoryStore reopened = service.openStore("scope.a", historyDir, HistoryPolicy.defaults());
        assertEquals(1, reopened.deletedEntries().size(), "the queued delete mark must be stored");
    }

    /** A store closed on the EDT may only wait briefly, not the full 30 seconds. */
    @Test
    void closeStoreOnTheEventThreadGivesUpQuickly() throws Exception {
        Path historyDir = dir.resolve("history");
        LocalHistoryStore store = service.openStore("scope.a", historyDir, HistoryPolicy.defaults());
        store.capture(request("v1")).join();
        CountDownLatch blocked = new CountDownLatch(1);
        executor.submit(() -> blocked.await(30, TimeUnit.SECONDS));
        store.markDeletedLater(KEY, clock.millis());
        try {
            long start = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> service.closeStore("scope.a"));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(elapsedMillis < 5_000L, "waits at most a second on the EDT; took " + elapsedMillis + " ms");
        } finally {
            blocked.countDown();
        }
    }

    @Test
    void brokenDatabaseIsQuarantinedAndRebuilt() throws Exception {
        Path historyDir = dir.resolve("history");
        Files.createDirectories(historyDir);
        Files.writeString(historyDir.resolve("history.sqlite"), "this is not a database");
        LocalHistoryStore store = service.openStore("scope.a", historyDir, HistoryPolicy.defaults());
        assertTrue(store.stats().available(), "a broken database is renamed and rebuilt, so history stays available");
        assertTrue(store.capture(request("v1")).join().captured());
        try (Stream<Path> files = Files.list(historyDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("history.sqlite.broken-")));
        }
    }

    /**
     * A database that fails to open without being corrupt (full disk, network I/O error, permissions) must not be
     * quarantined: that would hide a history that opens fine next time.
     */
    @Test
    void environmentFailureDoesNotQuarantineAGoodDatabase() throws Exception {
        Path historyDir = dir.resolve("history");
        Files.createDirectories(historyDir);
        Files.writeString(historyDir.resolve("history.sqlite"), "pretend-this-one-is-fine");
        DefaultLocalHistoryService failing = new DefaultLocalHistoryService(
                () -> new FailingDatabaseService(new StorageException("Failed to initialize database: x",
                        new SQLException("disk I/O error", "code", 10))),
                executor, scheduler, clock);
        try {
            LocalHistoryStore store = failing.openStore("scope.env", historyDir, HistoryPolicy.defaults());

            assertEquals(HistoryUnavailableReason.TEMPORARILY_UNAVAILABLE, store.stats().unavailableReason(),
                    "an open failure is reported as temporarily unavailable");
            try (Stream<Path> files = Files.list(historyDir)) {
                assertTrue(files.noneMatch(p -> p.getFileName().toString().contains(".broken-")),
                        "an environment failure must not quarantine the database");
            }
            assertEquals("pretend-this-one-is-fine", Files.readString(historyDir.resolve("history.sqlite")),
                    "the original database must stay as it was");
        } finally {
            failing.dispose();
        }
    }

    @Test
    void directoryThatCannotBeCreatedMakesHistoryUnavailable() throws Exception {
        Path historyDir = dir.resolve("history");
        Files.writeString(historyDir, "a file where the history directory should be");

        LocalHistoryStore store = service.openStore("scope.dir", historyDir, HistoryPolicy.defaults());

        assertEquals(HistoryUnavailableReason.DIRECTORY_UNAVAILABLE, store.stats().unavailableReason());
    }

    @Test
    void databaseStillFailingAfterRebuildMakesHistoryUnavailable() throws Exception {
        Path historyDir = dir.resolve("history");
        Files.createDirectories(historyDir);
        DefaultLocalHistoryService failing = new DefaultLocalHistoryService(
                () -> new FailingDatabaseService(new StorageException("Failed to initialize database: x",
                        new SQLException("file is not a database", "code", 26))),
                executor, scheduler, clock);
        try {
            LocalHistoryStore store = failing.openStore("scope.broken", historyDir, HistoryPolicy.defaults());

            assertEquals(HistoryUnavailableReason.OPEN_FAILED, store.stats().unavailableReason());
        } finally {
            failing.dispose();
        }
    }

    /** Fake DatabaseService whose open always throws the given exception. */
    private record FailingDatabaseService(StorageException failure) implements DatabaseService {
        @Override
        public DatabaseHandle open(DatabaseDescriptor descriptor) {
            throw failure;
        }

        @Override
        public Optional<DatabaseHandle> findOpen(String databaseId) {
            return Optional.empty();
        }

        @Override
        public boolean close(String databaseId) {
            return false;
        }
    }

    /** A database without the params column logs one ERROR naming the file and opens as is, without quarantine. */
    @Test
    void aDatabaseWithoutTheParamsColumnIsReportedAndLeftAlone() throws Exception {
        Path historyDir = dir.resolve("history");
        Path file = historyDir.resolve("history.sqlite");
        service.openStore("scope.a", historyDir, HistoryPolicy.defaults());
        assertTrue(service.closeStore("scope.a"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute("alter table lh_activities drop column params");
        }

        Logger logger = (Logger) LoggerFactory.getLogger(DefaultLocalHistoryService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        boolean additive = logger.isAdditive();
        logs.start();
        logger.setAdditive(false);
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logs);
        try {
            service.openStore("scope.a", historyDir, HistoryPolicy.defaults());
            service.openStore("scope.fresh", dir.resolve("fresh"), HistoryPolicy.defaults());
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(null);
            logger.setAdditive(additive);
            logs.stop();
        }

        List<String> errors = logs.list.stream().filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(List.of("Local history database " + file
                + " predates the lh_activities.params column; convert it before use"), errors, "only the old database");
        try (Stream<Path> files = Files.list(historyDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().contains(".broken-")), "not quarantined");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("select count(*) from pragma_table_info('lh_activities') where name = 'params'")) {
            assertTrue(columns.next());
            assertEquals(0, columns.getInt(1), "the probe only reads and doesn't add the column");
        }
    }

    @Test
    void scopeIdIsSanitizedForDescriptor() {
        assertEquals("myapp.project.abc-1", DefaultLocalHistoryService.sanitize("myapp.project.abc-1"));
        assertEquals("a_b_c", DefaultLocalHistoryService.sanitize("a/b:c"));
        assertEquals("s_x", DefaultLocalHistoryService.sanitize("_x"));
    }
}
