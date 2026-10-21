package com.jdesk.frame.webview;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Creation options for a frame-level WebView.
 */
public final class WebViewOptions {

    private final WebViewRenderingMode renderingMode;
    private final String initialUrl;
    private final String initialHtml;
    private final Path userDataDirectory;
    private final WebViewNavigationPolicy navigationPolicy;

    private WebViewOptions(Builder builder) {
        this.renderingMode = builder.renderingMode;
        this.initialUrl = trimToNull(builder.initialUrl);
        this.initialHtml = trimToNull(builder.initialHtml);
        this.userDataDirectory = builder.userDataDirectory;
        this.navigationPolicy = builder.navigationPolicy;
        if (initialUrl != null && initialHtml != null) {
            throw new IllegalStateException("Only one of initialUrl or initialHtml can be configured.");
        }
    }

    public static WebViewOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public WebViewRenderingMode getRenderingMode() {
        return renderingMode;
    }

    public Optional<String> getInitialUrl() {
        return Optional.ofNullable(initialUrl);
    }

    public Optional<String> getInitialHtml() {
        return Optional.ofNullable(initialHtml);
    }

    public Optional<Path> getUserDataDirectory() {
        return Optional.ofNullable(userDataDirectory);
    }

    /** Policy for main-frame navigation and popups; by default only the origin of the view's own document. */
    public WebViewNavigationPolicy getNavigationPolicy() {
        return navigationPolicy;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Builder {

        private WebViewRenderingMode renderingMode = WebViewRenderingMode.HARDWARE_ACCELERATED;
        private String initialUrl;
        private String initialHtml;
        private Path userDataDirectory;
        private WebViewNavigationPolicy navigationPolicy = WebViewNavigationPolicy.sameOrigin();

        private Builder() {
        }

        public Builder renderingMode(WebViewRenderingMode renderingMode) {
            this.renderingMode = Objects.requireNonNull(renderingMode, "renderingMode");
            return this;
        }

        public Builder initialUrl(String initialUrl) {
            this.initialUrl = initialUrl;
            return this;
        }

        public Builder initialHtml(String initialHtml) {
            this.initialHtml = initialHtml;
            return this;
        }

        public Builder userDataDirectory(Path userDataDirectory) {
            this.userDataDirectory = userDataDirectory;
            return this;
        }

        public Builder navigationPolicy(WebViewNavigationPolicy navigationPolicy) {
            this.navigationPolicy = Objects.requireNonNull(navigationPolicy, "navigationPolicy");
            return this;
        }

        public WebViewOptions build() {
            return new WebViewOptions(this);
        }
    }
}
