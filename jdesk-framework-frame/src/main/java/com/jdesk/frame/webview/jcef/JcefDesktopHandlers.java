package com.jdesk.frame.webview.jcef;

import com.jdesk.frame.ui.NativeFileDialogs;
import com.jdesk.frame.webview.WebFileDialogFilters;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.callback.CefFileDialogCallback;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;
import org.cef.handler.CefDialogHandler;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;

/**
 * Desktop integration for JCEF: page file pickers and download saves use the modern Windows common dialogs, and the
 * native context menu is suppressed.
 * Treat every failure as a cancel: answer each file dialog callback exactly once so Chromium never waits forever.
 */
final class JcefDesktopHandlers {

    private static final Logger LOG = LoggerFactory.getLogger(JcefDesktopHandlers.class);

    private JcefDesktopHandlers() {
    }

    /** Handles {@code <input type=file>} and save dialogs; this JCEF has no folder mode, so Chromium keeps those. */
    static CefDialogHandler fileDialogs() {
        return (browser, mode, title, defaultFilePath, acceptFilters, callback) -> {
            try {
                List<NativeFileDialogs.FileTypeFilter> filters =
                        WebFileDialogFilters.filters(null, acceptFilters, acceptFilters == null || acceptFilters.isEmpty());
                switch (mode) {
                    case FILE_DIALOG_OPEN -> pickOne(browser, filters, callback);
                    case FILE_DIALOG_OPEN_MULTIPLE -> pickMany(browser, filters, callback);
                    case FILE_DIALOG_SAVE -> pickSave(browser, defaultFilePath, filters, callback);
                    default -> callback.Cancel();
                }
                return true;
            } catch (RuntimeException failure) {
                LOG.warn("jcef.fileDialog.failed mode={}", mode, failure);
                callback.Cancel();
                return true;
            }
        };
    }

    private static void pickOne(CefBrowser browser, List<NativeFileDialogs.FileTypeFilter> filters,
                                CefFileDialogCallback callback) {
        NativeFileDialogs.pickFile(ownerOf(browser), null, filters)
                .whenComplete((picked, failure) -> completeOne(callback, failure, picked));
    }

    private static void pickMany(CefBrowser browser, List<NativeFileDialogs.FileTypeFilter> filters,
                                 CefFileDialogCallback callback) {
        NativeFileDialogs.pickFiles(ownerOf(browser), null, filters)
                .whenComplete((picked, failure) -> {
                    if (failure != null || picked == null || picked.isEmpty()) {
                        callback.Cancel();
                        return;
                    }
                    Vector<String> paths = new Vector<>();
                    picked.forEach(path -> paths.add(path.toString()));
                    callback.Continue(paths);
                });
    }

    private static void pickSave(CefBrowser browser, String defaultFilePath,
                                 List<NativeFileDialogs.FileTypeFilter> filters, CefFileDialogCallback callback) {
        NativeFileDialogs.pickSaveFile(ownerOf(browser), null, fileNameOf(defaultFilePath), filters)
                .whenComplete((picked, failure) -> completeOne(callback, failure, picked));
    }

    private static void completeOne(CefFileDialogCallback callback, Throwable failure, Optional<Path> picked) {
        if (failure != null || picked == null || picked.isEmpty()) {
            callback.Cancel();
            return;
        }
        Vector<String> paths = new Vector<>();
        paths.add(picked.orElseThrow().toString());
        callback.Continue(paths);
    }

    /** Asks where to save each download first; cancelling the dialog cancels the download explicitly. */
    static CefDownloadHandlerAdapter downloads() {
        return new DownloadHandler((owner, suggestedName) -> NativeFileDialogs.pickSaveFile(owner, null,
                suggestedName, WebFileDialogFilters.filters(null, List.of(), true)));
    }

    /** Shows a save dialog, completing with the chosen path or empty on cancel. */
    @FunctionalInterface
    interface SaveDialog {
        CompletableFuture<Optional<Path>> pick(Window owner, String suggestedName);
    }

    /**
     * Matches save dialogs with download progress by download id: a cancel uses the latest progress callback at once
     * and cancels every later one too.
     * Don't cancel by not calling back: a collected JBR callback calls {@code Continue("", false)} and saves to temp.
     */
    static final class DownloadHandler extends CefDownloadHandlerAdapter {
        private final SaveDialog saveDialog;
        private final Object lock = new Object();
        /** Downloads whose dialog is open, mapped to their latest progress callback, or null before the first. */
        private final Map<Integer, CefDownloadItemCallback> awaitingChoice = new HashMap<>();
        private final Set<Integer> cancelled = new HashSet<>();

        DownloadHandler(SaveDialog saveDialog) {
            this.saveDialog = saveDialog;
        }

        /** {@code item} is valid only during this call: read its id and URL first, never in the dialog callback. */
        @Override
        public void onBeforeDownload(CefBrowser browser, CefDownloadItem item,
                                     String suggestedName, CefBeforeDownloadCallback callback) {
            int id = item.getId();
            String url = item.getURL();
            synchronized (lock) {
                awaitingChoice.put(id, null);
            }
            CompletableFuture<Optional<Path>> choice;
            try {
                choice = saveDialog.pick(ownerOf(browser), suggestedName);
            } catch (RuntimeException failure) {
                choice = CompletableFuture.failedFuture(failure);
            }
            choice.whenComplete((picked, failure) -> {
                if (failure != null || picked == null || picked.isEmpty()) {
                    if (failure != null) {
                        LOG.warn("jcef.download.dialog.failed url={}", url, failure);
                    } else {
                        LOG.info("jcef.download.cancelled url={}", url);
                    }
                    cancel(id);
                    return;
                }
                synchronized (lock) {
                    awaitingChoice.remove(id);
                }
                callback.Continue(picked.orElseThrow().toString(), false);
            });
        }

        @Override
        public void onDownloadUpdated(CefBrowser browser, CefDownloadItem item,
                                      CefDownloadItemCallback callback) {
            int id = item.getId();
            boolean finished = item.isComplete() || item.isCanceled();
            boolean cancelNow = false;
            synchronized (lock) {
                if (finished) {
                    awaitingChoice.remove(id);
                    cancelled.remove(id);
                } else if (cancelled.contains(id)) {
                    cancelNow = true;
                } else if (awaitingChoice.containsKey(id)) {
                    awaitingChoice.put(id, callback);
                }
            }
            if (cancelNow) {
                callback.cancel();
            }
            if (item.isComplete()) {
                LOG.info("jcef.download.done path={} bytes={}", item.getFullPath(), item.getReceivedBytes());
            } else if (item.isCanceled()) {
                LOG.info("jcef.download.aborted url={}", item.getURL());
            }
        }

        private void cancel(int id) {
            CefDownloadItemCallback latest;
            synchronized (lock) {
                latest = awaitingChoice.remove(id);
                cancelled.add(id);
            }
            if (latest != null) {
                latest.cancel();
            }
        }
    }

    /** Suppresses the native context menu; pages implement their own. */
    static CefContextMenuHandlerAdapter contextMenu() {
        return new CefContextMenuHandlerAdapter() {
            @Override
            public void onBeforeContextMenu(CefBrowser browser, CefFrame frame,
                                            CefContextMenuParams params, CefMenuModel model) {
                model.clear();
            }
        };
    }

    /** Looks up the owner window on use; by callback time the view is in a window. */
    private static Window ownerOf(CefBrowser browser) {
        return browser == null ? null : SwingUtilities.getWindowAncestor(browser.getUIComponent());
    }

    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        Path name = Paths.get(path).getFileName();
        return name == null ? "" : name.toString();
    }
}
