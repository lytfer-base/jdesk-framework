package com.jdesk.frame.webview.jcef;

import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Matches the download save dialog to download progress: cancelling must cancel, and a chosen path is kept. */
final class JcefDesktopHandlersTest {

    private final CompletableFuture<Optional<Path>> choice = new CompletableFuture<>();
    private final JcefDesktopHandlers.DownloadHandler handler =
            new JcefDesktopHandlers.DownloadHandler((owner, suggestedName) -> choice);
    private final List<String> continued = new ArrayList<>();

    @Test
    void cancellingTheSaveDialogCancelsTheDownloadAndEveryLaterUpdate() {
        handler.onBeforeDownload(null, item(7, State.IN_PROGRESS), "a.bin", (path, showDialog) -> continued.add(path));
        RecordingCallback whileDialogOpen = new RecordingCallback();
        handler.onDownloadUpdated(null, item(7, State.IN_PROGRESS), whileDialogOpen);

        choice.complete(Optional.empty());
        RecordingCallback later = new RecordingCallback();
        handler.onDownloadUpdated(null, item(7, State.IN_PROGRESS), later);

        assertEquals(1, whileDialogOpen.cancels, "cancelling the dialog cancels via the latest callback");
        assertEquals(1, later.cancels, "later progress callbacks are cancelled too");
        assertEquals(List.of(), continued, "no Continue after cancelling");
    }

    @Test
    void cancellingBeforeAnyProgressCancelsOnTheNextUpdate() {
        handler.onBeforeDownload(null, item(8, State.IN_PROGRESS), "b.bin", (path, showDialog) -> continued.add(path));

        choice.complete(Optional.empty());
        RecordingCallback first = new RecordingCallback();
        handler.onDownloadUpdated(null, item(8, State.IN_PROGRESS), first);

        assertEquals(1, first.cancels);
    }

    @Test
    void pickingAPathContinuesAndLeavesTheDownloadRunning() {
        handler.onBeforeDownload(null, item(9, State.IN_PROGRESS), "c.bin", (path, showDialog) -> continued.add(path));
        RecordingCallback whileDialogOpen = new RecordingCallback();
        handler.onDownloadUpdated(null, item(9, State.IN_PROGRESS), whileDialogOpen);

        Path target = Path.of("C:", "Downloads", "c.bin");
        choice.complete(Optional.of(target));
        RecordingCallback later = new RecordingCallback();
        handler.onDownloadUpdated(null, item(9, State.IN_PROGRESS), later);

        assertEquals(List.of(target.toString()), continued);
        assertEquals(0, whileDialogOpen.cancels + later.cancels);
    }

    private enum State { IN_PROGRESS, COMPLETE, CANCELED }

    /** Fake download item that answers id, state and URL; other methods return defaults. */
    private static CefDownloadItem item(int id, State state) {
        return (CefDownloadItem) Proxy.newProxyInstance(CefDownloadItem.class.getClassLoader(),
                new Class<?>[]{CefDownloadItem.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "isComplete" -> state == State.COMPLETE;
                    case "isCanceled" -> state == State.CANCELED;
                    case "isInProgress", "isValid" -> state == State.IN_PROGRESS;
                    case "getURL" -> "http://myapp.localhost/download/" + id;
                    case "getTotalBytes", "getReceivedBytes", "getCurrentSpeed" -> 0L;
                    case "getPercentComplete" -> 0;
                    default -> null;
                });
    }

    private static final class RecordingCallback implements CefDownloadItemCallback {
        private int cancels;

        @Override
        public void cancel() {
            cancels++;
        }

        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }
    }
}
