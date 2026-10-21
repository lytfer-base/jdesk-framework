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
 * Manual Level 1 compatibility surface for comparing WebView backends.
 */
public final class WebViewLevel1CompatibilityPanel {

    private WebViewLevel1CompatibilityPanel() {
    }

    public static JComponent create(Application app) {
        JPanel host = new JPanel(new BorderLayout(0, 8));
        try {
            WebViewService webViewService = app.getService(WebViewService.class);
            String backendName = webViewService.getBackendName();
            ReloadableWebView view = new ReloadableWebView(webViewService, () -> createLevel1Html(backendName), host);

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

    static String createLevel1Html(String backendName) {
        return """
                <!doctype html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>JDesk WebView Level 1 Compatibility</title>
                    <style>
                        :root {
                            color-scheme: light;
                            --scale: 1;
                        }
                        body {
                            margin: 0;
                            font-family: Inter, Segoe UI, Arial, sans-serif;
                            color: #172033;
                            background: #f5f7fa;
                        }
                        header {
                            position: sticky;
                            top: 0;
                            z-index: 2;
                            padding: 18px 24px;
                            background: #ffffff;
                            border-bottom: 1px solid #d7dde8;
                        }
                        h1 {
                            margin: 0 0 6px;
                            font-size: 22px;
                            line-height: 1.2;
                        }
                        .meta {
                            color: #526073;
                            font-size: 13px;
                        }
                        main {
                            display: grid;
                            grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
                            gap: 14px;
                            padding: 18px 24px 24px;
                            transform: scale(var(--scale));
                            transform-origin: top left;
                        }
                        section {
                            min-height: 170px;
                            padding: 16px;
                            background: #ffffff;
                            border: 1px solid #d7dde8;
                            border-radius: 8px;
                        }
                        h2 {
                            margin: 0 0 10px;
                            font-size: 15px;
                        }
                        p {
                            margin: 8px 0;
                            line-height: 1.45;
                        }
                        button,
                        input[type="file"]::file-selector-button {
                            margin: 4px 6px 4px 0;
                            padding: 7px 10px;
                            border: 1px solid #bbc5d6;
                            border-radius: 6px;
                            color: #172033;
                            background: #f9fafc;
                            cursor: pointer;
                        }
                        input,
                        textarea,
                        [contenteditable] {
                            width: 100%;
                            box-sizing: border-box;
                            margin: 4px 0;
                            padding: 8px;
                            border: 1px solid #bbc5d6;
                            border-radius: 6px;
                            font: inherit;
                        }
                        textarea {
                            min-height: 66px;
                        }
                        .drop-zone {
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            min-height: 76px;
                            border: 1px dashed #8fa0b8;
                            border-radius: 8px;
                            background: #f9fbff;
                            user-select: none;
                        }
                        #log {
                            grid-column: 1 / -1;
                            min-height: 140px;
                            white-space: pre-wrap;
                            font-family: Consolas, Menlo, monospace;
                            font-size: 12px;
                            color: #203047;
                            background: #eef3f9;
                        }
                        a {
                            color: #1d4ed8;
                        }
                    </style>
                </head>
                <body>
                    <header>
                        <h1>WebView Level 1 Compatibility</h1>
                        <div class="meta">Backend: __BACKEND__ · Run the same page with JCEF and JxBrowser, then record differences.</div>
                    </header>
                    <main>
                        <section data-scenario="context-menu">
                            <h2>Context Menu</h2>
                            <p>Right-click the area below and watch whether the page event and native menu appear.</p>
                            <div id="context-area" class="drop-zone">Right-click target</div>
                        </section>
                
                        <section data-scenario="file-chooser">
                            <h2>File Chooser</h2>
                            <p>Select or cancel a file dialog.</p>
                            <input id="file-input" type="file" multiple>
                            <p id="file-result">No file selected.</p>
                        </section>
                
                        <section data-scenario="download">
                            <h2>Download</h2>
                            <p><a id="data-download" download="jdesk-level1-data.txt"
                                  href="data:text/plain;charset=utf-8,JDesk%20Level%201%20download">Download data URL</a></p>
                            <button id="blob-download" type="button">Download blob</button>
                        </section>
                
                        <section data-scenario="clipboard">
                            <h2>Clipboard</h2>
                            <input id="clipboard-text" value="JDesk clipboard text">
                            <button id="clipboard-write" type="button">Write</button>
                            <button id="clipboard-read" type="button">Read</button>
                            <button id="clipboard-copy-fallback" type="button">Fallback copy</button>
                        </section>
                
                        <section data-scenario="navigation">
                            <h2>Navigation</h2>
                            <p><a id="hash-link" href="#level1-hash-target">Hash navigation</a></p>
                            <p><a id="external-link" href="https://example.com/">External navigation</a></p>
                            <button id="open-window" type="button">window.open</button>
                        </section>
                
                        <section data-scenario="zoom">
                            <h2>Zoom</h2>
                            <button id="zoom-out" type="button">-</button>
                            <button id="zoom-reset" type="button">Reset</button>
                            <button id="zoom-in" type="button">+</button>
                            <p id="zoom-result">CSS scale: 1.00</p>
                        </section>
                
                        <section data-scenario="focus">
                            <h2>Focus</h2>
                            <input id="focus-input" placeholder="Input focus target">
                            <textarea id="focus-textarea">Textarea focus target</textarea>
                            <div id="editable" contenteditable="true">Editable text target</div>
                            <button id="focus-button" type="button">Focus input</button>
                        </section>
                
                        <section data-scenario="permissions">
                            <h2>Permissions</h2>
                            <button id="notify-permission" type="button">Notification</button>
                            <button id="geo-permission" type="button">Geolocation</button>
                            <button id="media-permission" type="button">Media</button>
                        </section>
                
                        <section id="log" aria-live="polite">Log ready.</section>
                    </main>
                    <script>
                        (function () {
                            const logNode = document.getElementById('log');
                            let zoom = 1;
                            const entries = [];
                
                            function log(message) {
                                const text = new Date().toLocaleTimeString() + '  ' + message;
                                entries.push(text);
                                logNode.textContent = entries.slice(-30).join('\\n');
                            }
                
                            function outcome(label, promise) {
                                Promise.resolve(promise)
                                    .then(value => log(label + ': ' + String(value)))
                                    .catch(error => log(label + ' failed: ' + (error && error.message ? error.message : error)));
                            }
                
                            document.getElementById('context-area').addEventListener('contextmenu', event => {
                                log('contextmenu at ' + event.clientX + ',' + event.clientY);
                            });
                
                            document.getElementById('file-input').addEventListener('change', event => {
                                const files = Array.from(event.target.files || []).map(file => file.name);
                                document.getElementById('file-result').textContent = files.length ? files.join(', ') : 'No file selected.';
                                log('file chooser changed: ' + (files.length ? files.join(', ') : 'empty'));
                            });
                
                            document.getElementById('blob-download').addEventListener('click', () => {
                                const blob = new Blob(['JDesk Level 1 blob download\\n'], {type: 'text/plain'});
                                const url = URL.createObjectURL(blob);
                                const link = document.createElement('a');
                                link.href = url;
                                link.download = 'jdesk-level1-blob.txt';
                                document.body.appendChild(link);
                                link.click();
                                link.remove();
                                setTimeout(() => URL.revokeObjectURL(url), 1000);
                                log('blob download requested');
                            });
                
                            document.getElementById('clipboard-write').addEventListener('click', () => {
                                const value = document.getElementById('clipboard-text').value;
                                if (!navigator.clipboard || !navigator.clipboard.writeText) {
                                    log('clipboard write API unavailable');
                                    return;
                                }
                                outcome('clipboard write', navigator.clipboard.writeText(value).then(() => 'ok'));
                            });
                
                            document.getElementById('clipboard-read').addEventListener('click', () => {
                                if (!navigator.clipboard || !navigator.clipboard.readText) {
                                    log('clipboard read API unavailable');
                                    return;
                                }
                                outcome('clipboard read', navigator.clipboard.readText());
                            });
                
                            document.getElementById('clipboard-copy-fallback').addEventListener('click', () => {
                                const input = document.getElementById('clipboard-text');
                                input.focus();
                                input.select();
                                log('execCommand copy: ' + document.execCommand('copy'));
                            });
                
                            document.getElementById('hash-link').addEventListener('click', () => log('hash navigation requested'));
                            document.getElementById('external-link').addEventListener('click', () => log('external navigation requested'));
                            document.getElementById('open-window').addEventListener('click', () => {
                                const opened = window.open('about:blank', '_blank');
                                log('window.open result: ' + (opened ? 'opened' : 'blocked/null'));
                            });
                
                            function setZoom(value) {
                                zoom = Math.max(0.75, Math.min(1.5, value));
                                document.documentElement.style.setProperty('--scale', zoom.toFixed(2));
                                document.getElementById('zoom-result').textContent = 'CSS scale: ' + zoom.toFixed(2);
                                log('zoom css scale set to ' + zoom.toFixed(2));
                            }
                            document.getElementById('zoom-out').addEventListener('click', () => setZoom(zoom - 0.1));
                            document.getElementById('zoom-reset').addEventListener('click', () => setZoom(1));
                            document.getElementById('zoom-in').addEventListener('click', () => setZoom(zoom + 0.1));
                
                            ['focus-input', 'focus-textarea', 'editable'].forEach(id => {
                                document.getElementById(id).addEventListener('focus', () => log('focus: ' + id));
                                document.getElementById(id).addEventListener('input', () => log('input: ' + id));
                            });
                            document.getElementById('focus-button').addEventListener('click', () => {
                                document.getElementById('focus-input').focus();
                            });
                
                            document.getElementById('notify-permission').addEventListener('click', () => {
                                if (!window.Notification || !Notification.requestPermission) {
                                    log('notification permission unavailable');
                                    return;
                                }
                                outcome('notification permission', Notification.requestPermission());
                            });
                
                            document.getElementById('geo-permission').addEventListener('click', () => {
                                if (!navigator.geolocation) {
                                    log('geolocation unavailable');
                                    return;
                                }
                                navigator.geolocation.getCurrentPosition(
                                    position => log('geolocation ok: ' + position.coords.latitude + ',' + position.coords.longitude),
                                    error => log('geolocation failed: ' + error.message),
                                    {timeout: 5000}
                                );
                            });
                
                            document.getElementById('media-permission').addEventListener('click', () => {
                                if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                                    log('media permission unavailable');
                                    return;
                                }
                                outcome('media permission', navigator.mediaDevices.getUserMedia({audio: true, video: false})
                                    .then(stream => {
                                        stream.getTracks().forEach(track => track.stop());
                                        return 'ok';
                                    }));
                            });
                
                            window.addEventListener('hashchange', () => log('hash changed: ' + location.hash));
                            window.jdeskLevel1 = {
                                summary: function () {
                                    return entries.slice(-12).join(' | ') || 'No Level 1 actions recorded yet.';
                                },
                                log: log
                            };
                            log('Level 1 page initialized');
                        })();
                    </script>
                </body>
                </html>
                """.replace("__BACKEND__", escapeHtml(backendName));
    }

    private static JComponent createToolbar(ReloadableWebView view, String backendName) {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);

        JButton reload = new JButton("Reload Level 1");
        reload.addActionListener(event -> view.reload());
        toolbar.add(reload);

        JLabel result = new JLabel(" ");
        JButton readSummary = new JButton("Read Summary");
        // The summary goes to the page's own log: the WebView API runs scripts but returns no values.
        readSummary.addActionListener(event -> {
            try {
                view.current().executeJavaScript("window.jdeskLevel1"
                        + " ? window.jdeskLevel1.log(window.jdeskLevel1.summary())"
                        + " : void 0;");
                result.setText("Summary written to page log");
            } catch (RuntimeException failure) {
                result.setText("Summary failed: " + describe(failure));
            }
        });
        toolbar.add(readSummary);
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
                WebView Level 1 compatibility surface is not available.
                
                Failure:
                %s
                
                Select a backend with:
                - JVM property: -Djdesk.webview.backend=auto|jcef|jxbrowser
                - Environment variable: JDESK_WEBVIEW_BACKEND=auto|jcef|jxbrowser
                """.formatted(describe(failure)));
        return new JScrollPane(area);
    }

    private static String escapeHtml(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
