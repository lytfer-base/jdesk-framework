package com.jdesk.demo;

import com.jdesk.core.app.Application;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.webview.JdfWebView;
import com.jdesk.frame.webview.WebViewService;
import com.jdesk.frame.webview.WebViewWarmupResult;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.event.HierarchyEvent;

/**
 * Demo-only panel for measuring WebView startup time in the current process.
 */
public final class WebViewStartupBenchmarkPanel extends JPanel {

    private static final int READY_POLL_INTERVAL_MS = 50;
    private static final long READY_TIMEOUT_NANOS = 15_000_000_000L;

    private final Application app;
    private final JButton runButton = new JButton("Run Benchmark");
    private final JButton prewarmButton = new JButton("Warm Up Then Benchmark");
    private final JProgressBar progressBar = new JProgressBar();
    private final JPanel browserHost = new JPanel(new BorderLayout());
    private final JTextArea resultsArea = new JTextArea(7, 80);

    private JdfWebView activeView;
    private Timer activeTimer;

    private WebViewStartupBenchmarkPanel(Application app) {
        super(new BorderLayout(0, 8));
        this.app = app;
        buildUi();
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0
                    && !isDisplayable()) {
                disposeActiveWebView();
            }
        });
    }

    public static JComponent create(Application app) {
        return new WebViewStartupBenchmarkPanel(app);
    }

    private void buildUi() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        runButton.addActionListener(event -> runBenchmark());
        toolbar.add(runButton);
        prewarmButton.addActionListener(event -> runBenchmarkWithWarmup());
        toolbar.add(prewarmButton);

        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        progressBar.setVisible(false);
        toolbar.addSeparator();
        toolbar.add(progressBar);
        add(toolbar, BorderLayout.NORTH);

        resultsArea.setEditable(false);
        resultsArea.setLineWrap(true);
        resultsArea.setWrapStyleWord(true);
        resultsArea.setText("""
                WebView startup benchmark.

                Sample 1 includes shared Engine initialization only when no WebView has already been created in this process.
                Sample 2 measures creating another WebView while the shared Engine is already alive.
                """);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                browserHost,
                new JScrollPane(resultsArea));
        splitPane.setResizeWeight(0.78);
        splitPane.setBorder(JdfUi.empty());
        add(splitPane, BorderLayout.CENTER);
    }

    private void runBenchmark() {
        prepareRun();
        appendLine("Running WebView startup benchmark...");
        appendLine("Sample 1 is cold only if this is the first WebView in the current process.");
        appendLine("");

        runSample("Sample 1", () -> runSample("Sample 2", this::finishBenchmark));
    }

    private void runBenchmarkWithWarmup() {
        prepareRun();
        appendLine("Running WebView startup benchmark with background WebView warmup...");
        try {
            WebViewService webViewService = app.getService(WebViewService.class);
            appendLine("Backend status before warmup: " + webViewService.getBackendStatus());
            progressBar.setString("Warmup");
            webViewService.warmUpViewAsync().whenComplete((result, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (failure != null) {
                            appendLine("Warmup: FAILED [" + describe(failure) + "]");
                        } else {
                            appendLine("Warmup: " + formatWarmupResult(result));
                            appendLine("Backend status after warmup: " + webViewService.getBackendStatus());
                        }
                        appendLine("");
                        runSample("Sample 1 after warmup", () ->
                                runSample("Sample 2 after warmup", this::finishBenchmark));
                    }));
        } catch (RuntimeException failure) {
            appendLine("Warmup setup failed: " + describe(failure));
            finishBenchmark();
        }
    }

    private void prepareRun() {
        runButton.setEnabled(false);
        prewarmButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        resultsArea.setText("");
    }

    private void runSample(String label, Runnable onComplete) {
        progressBar.setString(label);
        appendLine(label + ": starting...");
        long startNanos = System.nanoTime();
        try {
            WebViewService webViewService = app.getService(WebViewService.class);
            appendLine(label + ": backend=" + webViewService.getBackendName());
            long serviceResolvedNanos = System.nanoTime();
            JdfWebView webView = webViewService.createWebView();
            long webViewCreatedNanos = System.nanoTime();
            setActiveView(webView);
            webView.loadHtml(createBenchmarkHtml(label));
            long loadHtmlReturnedNanos = System.nanoTime();
            waitForReadyState(label, startNanos, serviceResolvedNanos, webViewCreatedNanos,
                    loadHtmlReturnedNanos, webView, onComplete);
        } catch (RuntimeException failure) {
            appendResult(WebViewStartupBenchmarkResult.failure(label, startNanos, System.nanoTime(), failure));
            onComplete.run();
        }
    }

    private void waitForReadyState(String label,
                                   long startNanos,
                                   long serviceResolvedNanos,
                                   long webViewCreatedNanos,
                                   long loadHtmlReturnedNanos,
                                   JdfWebView webView,
                                   Runnable onComplete) {
        final RuntimeException[] lastFailure = new RuntimeException[1];
        activeTimer = new Timer(READY_POLL_INTERVAL_MS, null);
        activeTimer.addActionListener(event -> {
            long now = System.nanoTime();
            try {
                // Detect completion by the loading state: the WebView API can't read document.readyState back.
                if (!webView.isLoading()) {
                    Timer timer = (Timer) event.getSource();
                    timer.stop();
                    if (activeTimer == timer) {
                        activeTimer = null;
                    }
                    appendResult(WebViewStartupBenchmarkResult.success(label,
                            startNanos,
                            serviceResolvedNanos,
                            webViewCreatedNanos,
                            loadHtmlReturnedNanos,
                            now));
                    onComplete.run();
                    return;
                }
            } catch (RuntimeException failure) {
                lastFailure[0] = failure;
            }

            if (now - startNanos > READY_TIMEOUT_NANOS) {
                Timer timer = (Timer) event.getSource();
                timer.stop();
                if (activeTimer == timer) {
                    activeTimer = null;
                }
                RuntimeException timeout = new RuntimeException("Timed out waiting for document.readyState=complete",
                        lastFailure[0]);
                appendResult(WebViewStartupBenchmarkResult.failure(label, startNanos, now, timeout));
                onComplete.run();
            }
        });
        activeTimer.setInitialDelay(0);
        activeTimer.start();
    }

    private void finishBenchmark() {
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        progressBar.setString("Ready");
        runButton.setEnabled(true);
        prewarmButton.setEnabled(true);
        appendLine("");
        appendLine("Benchmark finished. Re-run to measure warm startup again in this process.");
    }

    private static String formatWarmupResult(WebViewWarmupResult result) {
        String text = result.status() + " total=" + result.elapsedMillis() + " ms";
        return result.isSuccess() ? text : text + " [" + result.failureSummary() + "]";
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private void appendResult(WebViewStartupBenchmarkResult result) {
        appendLine(result.toSummaryLine());
    }

    private void appendLine(String line) {
        resultsArea.append(line);
        resultsArea.append(System.lineSeparator());
        resultsArea.setCaretPosition(resultsArea.getDocument().getLength());
    }

    private void setActiveView(JdfWebView webView) {
        disposeActiveWebView();
        activeView = webView;
        browserHost.removeAll();
        browserHost.add(webView.component(), BorderLayout.CENTER);
        browserHost.revalidate();
        browserHost.repaint();
    }

    private void disposeActiveWebView() {
        if (activeTimer != null) {
            activeTimer.stop();
            activeTimer = null;
        }
        if (activeView != null) {
            Disposer.dispose(activeView);
            activeView = null;
        }
        browserHost.removeAll();
        browserHost.revalidate();
        browserHost.repaint();
    }

    private static String createBenchmarkHtml(String label) {
        return """
                <!doctype html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>JDesk WebView Startup Benchmark</title>
                    <style>
                        body {
                            margin: 0;
                            font-family: Inter, Segoe UI, Arial, sans-serif;
                            color: #172033;
                            background: #f5f7fb;
                        }
                        main {
                            max-width: 760px;
                            margin: 48px auto;
                            padding: 28px 32px;
                            background: #fff;
                            border: 1px solid #d9e2ef;
                            border-radius: 8px;
                            box-shadow: 0 12px 32px rgba(23, 32, 51, 0.08);
                        }
                        h1 {
                            margin: 0 0 12px;
                            font-size: 30px;
                        }
                        p {
                            line-height: 1.55;
                        }
                    </style>
                </head>
                <body>
                    <main>
                        <h1>Startup Benchmark</h1>
                        <p>__LABEL__ rendered through WebViewService.</p>
                        <p id="ready">Document ready state: loading</p>
                    </main>
                    <script>
                        document.getElementById('ready').textContent =
                            'Document ready state: ' + document.readyState;
                    </script>
                </body>
                </html>
                """.replace("__LABEL__", label);
    }
}
