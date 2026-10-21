package com.jdesk.frame.ui;

import com.jdesk.core.app.Application;
import com.jdesk.frame.icon.JdfIconManager;

import java.util.Objects;

/** Installs the JDesk root pane and custom header on a standalone window. */
public final class JdfFrameShell {

    private final JdfWindowFrame frame;
    private final JdfRootPane rootPane;
    private final JdfHeader header;

    private JdfFrameShell(JdfWindowFrame frame, JdfRootPane rootPane, JdfHeader header) {
        this.frame = frame;
        this.rootPane = rootPane;
        this.header = header;
    }

    public static JdfFrameShell install(JdfWindowFrame frame,
                                        Application application,
                                        JdfHeaderOptions options) {
        Objects.requireNonNull(frame, "frame");
        JdfHeaderOptions effectiveOptions = options == null ? JdfHeaderOptions.defaults() : options;
        JdfRootPane rootPane = frame.getJdfRootPane();
        JdfHeader header = new JdfHeader(application, frame, effectiveOptions);
        header.primeCustomTitleBar();
        rootPane.installHeader(header);
        installApplicationIcon(frame, application);
        return new JdfFrameShell(frame, rootPane, header);
    }

    private static void installApplicationIcon(JdfWindowFrame frame, Application application) {
        if (application == null) {
            return;
        }
        try {
            application.getService(JdfIconManager.class).updateWindowIcon(frame);
        } catch (IllegalStateException ignored) {
            // The frame shell can be reused in tests or minimal hosts before icon services are registered.
        }
    }

    public JdfWindowFrame frame() {
        return frame;
    }

    public JdfRootPane rootPane() {
        return rootPane;
    }

    public JdfHeader header() {
        return header;
    }
}
