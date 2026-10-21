package com.jdesk.frame.webview.bridge.impl;

import com.jdesk.frame.webview.bridge.WebBridgeOptions;

import java.util.Objects;

/**
 * Generates the bridge script injected into pages. It exposes a single namespace such as
 * {@code window.jdeskBridge.invoke()} and {@code window.jdeskBridge.on()}, whatever the browser backend.
 */
public final class WebBridgeBootstrapScript {

    /**
     * Returns the bootstrap script for the configured namespace.
     *
     * @param nativePostExpression the backend's low-level send function, such as JCEF's {@code window.jdeskQuery}
     */
    public String create(WebBridgeOptions options, String nativePostExpression) {
        WebBridgeOptions actualOptions = options == null ? WebBridgeOptions.defaults() : options;
        String bridgeReference = namespaceReference(actualOptions.namespace());
        String endpoint = Objects.requireNonNull(nativePostExpression, "nativePostExpression").trim();
        if (endpoint.isEmpty()) {
            throw new IllegalArgumentException("Native post expression is required.");
        }
        return """
                (function() {
                    if (%s) {
                        window.dispatchEvent(new CustomEvent('jdesk-bridge-ready'));
                        return;
                    }
                    let sequence = 0;
                    const pending = {};
                    function nextId() {
                        sequence += 1;
                        return 'jdesk-' + Date.now().toString(36) + '-' + sequence.toString(36);
                    }
                    const listeners = {};
                    function normalizeEvent(event) {
                        return String(event || '').trim();
                    }
                    window.__jdeskBridgeReceive = function(response, fallbackId) {
                        const payload = typeof response === 'string' ? JSON.parse(response) : response;
                        // An undecodable reply may lack its id; use the caller's id so the promise still settles
                        const id = (payload && payload.id) || fallbackId;
                        const item = id && pending[id];
                        if (!item) {
                            return;
                        }
                        delete pending[id];
                        if (payload.ok) {
                            item.resolve(payload.result);
                        } else {
                            item.reject(payload.error);
                        }
                    };
                    window.__jdeskBridgeEmit = function(message) {
                        const payload = typeof message === 'string' ? JSON.parse(message) : message;
                        const event = payload && normalizeEvent(payload.event);
                        const handlers = event && listeners[event];
                        if (!handlers) {
                            return;
                        }
                        handlers.slice().forEach(function(handler) {
                            try {
                                handler(payload.payload);
                            } catch (error) {
                                setTimeout(function() { throw error; }, 0);
                            }
                        });
                    };
                    %s = {
                        invoke: function(method, params) {
                            const id = nextId();
                            const request = { id: id, method: method, params: params };
                            return new Promise(function(resolve, reject) {
                                pending[id] = { resolve: resolve, reject: reject };
                                try {
                                    %s;
                                } catch (error) {
                                    delete pending[id];
                                    reject(error);
                                }
                            });
                        },
                        on: function(event, handler) {
                            const key = normalizeEvent(event);
                            if (!key) {
                                throw new Error('WebBridge event is required.');
                            }
                            if (typeof handler !== 'function') {
                                throw new Error('WebBridge event handler must be a function.');
                            }
                            const handlers = listeners[key] || (listeners[key] = []);
                            handlers.push(handler);
                            return function() {
                                %s.off(key, handler);
                            };
                        },
                        off: function(event, handler) {
                            const key = normalizeEvent(event);
                            const handlers = key && listeners[key];
                            if (!handlers) {
                                return;
                            }
                            const index = handlers.indexOf(handler);
                            if (index >= 0) {
                                handlers.splice(index, 1);
                            }
                            if (handlers.length === 0) {
                                delete listeners[key];
                            }
                        }
                    };
                    window.dispatchEvent(new CustomEvent('jdesk-bridge-ready'));
                })();
                """.formatted(bridgeReference, bridgeReference, postCall(endpoint), bridgeReference);
    }

    /**
     * Builds the expression the page sends requests with; the JCEF MessageRouter's onSuccess and onFailure callbacks
     * are routed to {@code __jdeskBridgeReceive}.
     */
    private static String postCall(String endpoint) {
        if ("window.jdeskQuery".equals(endpoint)) {
            return """
                    window.jdeskQuery({
                                            request: JSON.stringify(request),
                                            onSuccess: function(response) {
                                                window.__jdeskBridgeReceive(response, id);
                                            },
                                            onFailure: function(errorCode, errorMessage) {
                                                window.__jdeskBridgeReceive({
                                                    id: id,
                                                    ok: false,
                                                    error: {
                                                        code: 'HANDLER_FAILED',
                                                        message: errorMessage || String(errorCode)
                                                    }
                                                });
                                            }
                                        })""";
        }
        return endpoint + "(JSON.stringify(request))";
    }

    /** Turns the configured namespace into a safe {@code window} property access. */
    private static String namespaceReference(String namespace) {
        if (namespace.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return "window." + namespace;
        }
        return "window['" + escapeJavaScript(namespace) + "']";
    }

    private static String escapeJavaScript(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
