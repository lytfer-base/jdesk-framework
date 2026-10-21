package com.jdesk.demo;

import com.jdesk.core.app.Application;
import com.jdesk.frame.webview.WebViewService;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import java.awt.BorderLayout;
import java.awt.event.HierarchyEvent;

/**
 * Demo surface for validating the frame-level WebView service.
 */
public final class WebViewDemoPanel {

    private WebViewDemoPanel() {
    }

    public static JComponent create(Application app) {
        JPanel host = new JPanel(new BorderLayout(0, 8));
        try {
            WebViewService webViewService = app.getService(WebViewService.class);
            String backendName = webViewService.getBackendName();
            ReloadableWebView view = new ReloadableWebView(webViewService, () -> createDemoHtml(backendName), host);

            host.add(createToolbar(view, backendName), BorderLayout.NORTH);
            host.addHierarchyListener(event -> {
                if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0
                        && !host.isDisplayable()) {
                    view.dispose();
                }
            });
            return host;
        } catch (RuntimeException failure) {
            return createUnavailablePanel(failure);
        }
    }

    private static JComponent createToolbar(ReloadableWebView view, String backendName) {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);

        JButton reload = new JButton("Reload HTML");
        reload.addActionListener(event -> view.reload());
        toolbar.add(reload);

        JLabel result = new JLabel(" ");
        JButton runScript = new JButton("Run JS");
        // The script reports through the page title: the WebView API runs scripts but returns no values.
        runScript.addActionListener(event -> {
            try {
                view.current().executeJavaScript("""
                        document.getElementById('status').textContent =
                            'JavaScript executed from Swing at ' + new Date().toLocaleTimeString();
                        document.title = 'ping: ' + window.jdeskDemo.ping();
                        """);
                result.setText("Sent; see page and title");
            } catch (RuntimeException failure) {
                result.setText("JS failed: " + describe(failure));
            }
        });
        toolbar.add(runScript);
        toolbar.addSeparator();
        toolbar.add(new JLabel("Backend: " + backendName));
        toolbar.addSeparator();
        toolbar.add(result);
        return toolbar;
    }

    private static JComponent createUnavailablePanel(RuntimeException failure) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setText("""
                WebView backend is not available.

                Failure:
                %s

                Select a backend with:
                - JVM property: -Djdesk.webview.backend=auto|jcef|jxbrowser
                - Environment variable: JDESK_WEBVIEW_BACKEND=auto|jcef|jxbrowser
                - Local config file: jdesk.webview.backend=auto|jcef|jxbrowser

                The JCEF backend needs a JetBrains Runtime with JCEF. The jxbrowser backend
                is not part of this repository.
                """.formatted(describe(failure)));
        return new JScrollPane(area);
    }

    private static String createDemoHtml(String backendName) {
        return """
                <!doctype html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>JDesk WebView Demo</title>
                    <style>
                        body {
                            margin: 0;
                            font-family: Inter, Segoe UI, Arial, sans-serif;
                            color: #172033;
                            background: #f4f7fb;
                        }
                        main {
                            max-width: 760px;
                            margin: 56px auto;
                            padding: 32px;
                            background: white;
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
                        button {
                            margin-top: 12px;
                            padding: 10px 14px;
                            border: 0;
                            border-radius: 6px;
                            color: white;
                            background: #2563eb;
                            cursor: pointer;
                        }
                    </style>
                </head>
                <body>
                    <main>
                        <h1>JDesk WebView Demo</h1>
                        <p id="status">__BACKEND__ is rendering HTML through jdesk-framework-frame.</p>
                        <p>This verifies the application layer can consume WebViewService without depending on backend APIs.</p>
                        <button onclick="document.getElementById('status').textContent =
                            'Browser click handled at ' + new Date().toLocaleTimeString();">
                            Run browser JavaScript
                        </button>
                    </main>
                    <script>
                        window.jdeskDemo = {
                            ping: function () {
                                return 'pong from ' + document.title;
                            }
                        };
                    </script>
                </body>
                </html>
                """.replace("__BACKEND__", backendName);
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
