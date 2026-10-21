package com.jdesk.frame.ui;

import com.jdesk.core.app.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Base class for a window that shows its shell at once and assembles its content in the background (opening a
 * database, creating a WebView): a placeholder first, then {@link #loadInBackground()} on an I/O thread, then the
 * other template methods on the EDT to build the content or a fallback. Content loads once; a failure resets so the
 * next open retries, and {@link #dispose()} discards a load still in flight.
 *
 * @param <B> what the background load hands to the EDT; the base class only passes it on
 * @param <C> the content component installed in the window
 */
public abstract class JdfLazyContentFrame<B, C extends JComponent> extends JdfWindowFrame {

    private static final Logger LOG = LoggerFactory.getLogger(JdfLazyContentFrame.class);

    private final Application application;

    private final String loadTaskName;

    /**
     * The content load in flight, so that content loads once. Guarded by {@code this}; reset to {@code null} on
     * failure so that the next open retries.
     */
    private CompletableFuture<C> contentFuture;

    /** Installed content, or {@code null}; set on the EDT and read from any thread. */
    private volatile C content;

    /**
     * The submitted background load until it ends. Kept apart from {@link #contentFuture}, which reset and dispose
     * drop while the load may still be writing to disk; exit must wait for this one.
     */
    private CompletableFuture<?> runningLoad;

    /** Once set, no new load starts and late results are treated as stale. */
    private boolean disposed;

    /**
     * Creates the window shell without starting the content load; {@code application} and {@code loadTaskName}, the
     * background task's name, must not be null.
     */
    protected JdfLazyContentFrame(String title, Application application, String loadTaskName) {
        super(title);
        this.application = Objects.requireNonNull(application, "application");
        this.loadTaskName = Objects.requireNonNull(loadTaskName, "loadTaskName");
    }

    /**
     * Loads what the content needs on a background I/O thread, such as a database or non-GUI services; don't create
     * Swing components here. A throw shows the fallback content.
     */
    protected abstract B loadInBackground();

    /**
     * Builds the content component on the EDT from the background result; don't block on I/O here. A throw shows the
     * fallback content.
     */
    protected abstract C createContent(B loaded);

    /** Creates the placeholder shown while loading; called on the EDT. */
    protected abstract JComponent createLoadingPlaceholder();

    /** Creates the fallback shown when loading or building the content fails; called on the EDT. */
    protected abstract JComponent createUnavailableContent(Throwable failure);

    /**
     * Releases a background result that is discarded because the load ended after a reset or dispose, or because
     * {@link #createContent(Object)} failed; called on the EDT. Override it if the result holds threads or handles.
     */
    protected void disposeDiscardedBackground(B loaded) {
    }

    /** Returns the application, for services in the template methods. */
    protected final Application application() {
        return application;
    }

    /** Returns the installed content, or empty before it is installed and while the fallback is shown. */
    protected final Optional<C> currentContent() {
        return Optional.ofNullable(content);
    }

    /**
     * Starts loading the content unless it is loaded or loading; callable from any thread. The future completes with
     * the installed content, or exceptionally when loading fails.
     */
    public final CompletableFuture<C> ensureContentLoaded() {
        C installed = content;
        if (installed != null) {
            return CompletableFuture.completedFuture(installed);
        }

        CompletableFuture<C> future;
        synchronized (this) {
            if (content != null) {
                return CompletableFuture.completedFuture(content);
            }
            if (disposed) {
                return CompletableFuture.failedFuture(new CancellationException("window is disposed"));
            }
            if (contentFuture != null) {
                return contentFuture;
            }
            future = new CompletableFuture<>();
            contentFuture = future;
        }

        Runnable installLoading = () -> {
            if (content == null) {
                setContentPane(createLoadingPlaceholder());
                revalidate();
                repaint();
            }
            SwingUtilities.invokeLater(() -> loadAndInstall(future));
        };
        if (SwingUtilities.isEventDispatchThread()) {
            installLoading.run();
        } else {
            SwingUtilities.invokeLater(installLoading);
        }
        return future;
    }

    /**
     * Returns a stage that completes, never exceptionally, when this window's background load ends, a discarded one
     * included; it starts no load, and the load can't be cancelled. Exit must await it, as closing the window won't.
     * If the load ever starts later than {@code onFrameOpened}, make sure this method still sees it.
     */
    public final CompletionStage<?> pendingContentLoad() {
        CompletableFuture<?> running;
        CompletableFuture<C> inFlight;
        synchronized (this) {
            running = runningLoad;
            inFlight = contentFuture;
        }
        // Prefer the submitted load: it keeps writing after reset or dispose has dropped contentFuture.
        CompletableFuture<?> awaited = running != null ? running : inFlight;
        if (awaited == null) {
            return CompletableFuture.completedFuture(null);
        }
        // Complete normally even on failure, so that one fallback doesn't fail a caller's allOf.
        return awaited.handle((ignored, failure) -> null);
    }

    /**
     * Submits the background load from the EDT and installs its result back on the EDT. Rechecks the future first:
     * this runs queued, so a reset or dispose may have happened meanwhile.
     */
    private void loadAndInstall(CompletableFuture<C> future) {
        if (content != null) {
            future.complete(content);
            return;
        }
        boolean stale;
        synchronized (this) {
            stale = contentFuture != future;
        }
        if (stale) {
            future.completeExceptionally(new CancellationException("lazy content was reset"));
            return;
        }
        try {
            CompletableFuture<B> running = application.getTaskScope()
                    .asyncIo(loadTaskName, this::loadInBackground)
                    .asFuture();
            synchronized (this) {
                runningLoad = running;
            }
            running.whenComplete((loaded, error) -> {
                synchronized (this) {
                    if (runningLoad == running) {
                        runningLoad = null;
                    }
                }
                SwingUtilities.invokeLater(() -> install(future, loaded, error));
            });
        } catch (RuntimeException failure) {
            installUnavailable(future, failure);
        }
    }

    /**
     * Builds and installs the content on the EDT; idempotent, with the fallback on failure. A stale future (after a
     * reset or dispose) is cancelled and its result released through {@link #disposeDiscardedBackground(Object)}.
     * Don't release or complete while holding the monitor: subclass code and future callbacks run there.
     */
    private void install(CompletableFuture<C> future, B loaded, Throwable error) {
        boolean stale;
        synchronized (this) {
            stale = disposed || contentFuture != future;
        }
        if (stale) {
            if (error == null && loaded != null) {
                disposeDiscardedBackground(loaded);
            }
            future.completeExceptionally(new CancellationException("lazy content was reset"));
            return;
        }
        if (content != null) {
            future.complete(content);
            return;
        }
        if (error != null) {
            installUnavailable(future, error);
            return;
        }
        try {
            C created = createContent(loaded);
            setContentPane(created);
            content = created;
            revalidate();
            repaint();
            future.complete(created);
        } catch (RuntimeException | Error failure) {
            // Catch Error too (a backend class may fail to load), or the window would stay on the placeholder forever.
            if (loaded != null) {
                disposeDiscardedBackground(loaded);
            }
            installUnavailable(future, failure);
        }
    }

    /**
     * Fails the future; only while it is still the current load does it also clear it for a retry, install the
     * fallback and log a warning, so a discarded load never replaces the content of a reset window.
     */
    private void installUnavailable(CompletableFuture<C> future, Throwable failure) {
        boolean current;
        synchronized (this) {
            current = contentFuture == future;
            if (current) {
                contentFuture = null;
            }
        }
        if (current) {
            LOG.warn("lazyContent.unavailable frame={} task={}", getClass().getSimpleName(), loadTaskName, failure);
            setContentPane(createUnavailableContent(failure));
            revalidate();
            repaint();
        }
        future.completeExceptionally(failure);
    }

    /**
     * Drops the installed content and shows the placeholder, so the next {@link #ensureContentLoaded()} loads again;
     * a load in flight is cancelled and its result discarded. Call it on the EDT while the window is hidden.
     * The old content is released only by its own removeNotify; release anything else before calling.
     */
    protected final void resetLazyContent() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("resetLazyContent must run on the EDT");
        }
        synchronized (this) {
            contentFuture = null;
            content = null;
        }
        setContentPane(createLoadingPlaceholder());
        revalidate();
        repaint();
    }

    /**
     * Disposes the window and discards any load in flight; a late result goes to
     * {@link #disposeDiscardedBackground(Object)}, never into the disposed window.
     * Overrides must release their own resources first and call {@code super.dispose()} last.
     */
    @Override
    public void dispose() {
        synchronized (this) {
            disposed = true;
            contentFuture = null;
        }
        super.dispose();
    }
}
