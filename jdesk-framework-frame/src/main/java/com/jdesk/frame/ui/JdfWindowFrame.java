package com.jdesk.frame.ui;

import com.jdesk.frame.laf.JdfColors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Window with the JDesk custom root pane, since {@code JFrame#setRootPane} is protected. It also gives standalone
 * windows a minimal lifecycle: open, an asynchronous hook before closing, and disposal by default.
 */
public class JdfWindowFrame extends JFrame {

    private static final Logger LOG = LoggerFactory.getLogger(JdfWindowFrame.class);

    private final JdfRootPane jdfRootPane = new JdfRootPane();
    /** Closes in progress, at most one per reason; EDT only. */
    private final Map<JdfFrameCloseReason, CompletableFuture<Boolean>> closing =
            new EnumMap<>(JdfFrameCloseReason.class);

    public JdfWindowFrame(String title) {
        super(title);
        setRootPane(jdfRootPane);
        // The AWT window background shows until Swing paints the first frame; if left white, dark themes flash on open.
        setBackground(JdfColors.background());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeFrameAsync(JdfFrameCloseReason.WINDOW_CLOSE);
            }
        });
    }

    public JdfRootPane getJdfRootPane() {
        return jdfRootPane;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // Color the Win32 class brush as early as possible: it erases the first frame, which setBackground doesn't.
        NativeWindowBackground.apply(this, JdfColors.background());
        // Must set dark mode here, not on WINDOW_OPENED: the opening animation would use the light system panel color.
        NativeTitleBar.setDarkMode(this, JdfColors.isDark());
    }

    /** Shows the window and brings it to the front on the EDT, restoring it first if minimized. */
    public final void openFrame() {
        runOnEdt(() -> {
            if ((getExtendedState() & ICONIFIED) != 0) {
                setExtendedState(getExtendedState() & ~ICONIFIED);
            }
            setVisible(true);
            toFront();
            requestFocus();
            onFrameOpened();
        });
    }

    /**
     * Runs the before-close hook and, if it allows, the close action on the EDT; the future tells whether the window
     * closed. A call while a close for the same reason is in progress shares its result without running them again.
     */
    public final CompletableFuture<Boolean> closeFrameAsync(JdfFrameCloseReason reason) {
        JdfFrameCloseReason closeReason = Objects.requireNonNull(reason, "reason");
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        runOnEdt(() -> {
            CompletableFuture<Boolean> inFlight = closing.get(closeReason);
            if (inFlight != null && !inFlight.isDone()) {
                inFlight.whenComplete((allowed, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(allowed);
                    }
                });
                return;
            }
            closing.put(closeReason, result);
            result.whenComplete((allowed, failure) -> runOnEdt(() -> closing.remove(closeReason, result)));
            CompletionStage<Boolean> beforeClose;
            try {
                beforeClose = beforeFrameClose(closeReason);
            } catch (RuntimeException failure) {
                LOG.warn("frame.close.hookFailed frame={} reason={}", getClass().getSimpleName(), closeReason, failure);
                result.complete(false);
                return;
            }
            if (beforeClose == null) {
                beforeClose = CompletableFuture.completedFuture(true);
            }

            beforeClose.whenComplete((allowed, failure) -> {
                if (failure != null) {
                    LOG.warn("frame.close.hookFailed frame={} reason={}", getClass().getSimpleName(), closeReason,
                            failure);
                }
                if (failure != null || !Boolean.TRUE.equals(allowed)) {
                    result.complete(false);
                    return;
                }
                runOnEdt(() -> {
                    try {
                        closeFrameOnEdt(closeReason);
                        result.complete(true);
                    } catch (RuntimeException closeFailure) {
                        result.completeExceptionally(closeFailure);
                    }
                });
            });
        });
        return result;
    }

    /** Asynchronous hook before closing; completing with {@code false} vetoes the close. */
    protected CompletionStage<Boolean> beforeFrameClose(JdfFrameCloseReason reason) {
        return CompletableFuture.completedFuture(true);
    }

    /** Hook called after the window is shown. */
    protected void onFrameOpened() {
    }

    /** Close action; disposes the window by default. */
    protected void closeFrameOnEdt(JdfFrameCloseReason reason) {
        dispose();
    }

    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
