package com.jdesk.frame.webview.bridge;

import java.util.Objects;

/**
 * WebBridge session options: the page namespace and whether to inject the bridge on page load. Java puts no time
 * limit on a call; the page's invoke layer budgets timeouts per operation.
 */
public final class WebBridgeOptions {
    public static final String DEFAULT_NAMESPACE = "jdeskBridge";

    private final String namespace;
    private final boolean injectOnLoad;

    private WebBridgeOptions(Builder builder) {
        this.namespace = normalizeNamespace(builder.namespace);
        this.injectOnLoad = builder.injectOnLoad;
    }

    /** Returns the default options. */
    public static WebBridgeOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String namespace() {
        return namespace;
    }

    public boolean injectOnLoad() {
        return injectOnLoad;
    }

    private static String normalizeNamespace(String namespace) {
        String value = Objects.toString(namespace, "").trim();
        if (value.isEmpty()) {
            return DEFAULT_NAMESPACE;
        }
        return value;
    }

    public static final class Builder {
        private String namespace = DEFAULT_NAMESPACE;
        private boolean injectOnLoad = true;

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder injectOnLoad(boolean injectOnLoad) {
            this.injectOnLoad = injectOnLoad;
            return this;
        }

        public WebBridgeOptions build() {
            return new WebBridgeOptions(this);
        }
    }
}
