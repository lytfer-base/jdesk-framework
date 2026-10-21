package com.jdesk.frame.webview.resource.http;

import com.jdesk.core.app.Application;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.frame.webview.resource.WebResourceSchemeRegistry;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Loopback HTTP resource server: Jetty on a random 127.0.0.1 port hands {@code /<name>/<host>/<path>} to the
 * {@link WebResourceSchemeRegistry}. Pages use the port-less public origin {@code http://<publicHost>}, which WebView
 * backends map to the port by the {@link WebResourceSession} rules. Tokens change on every start.
 * Must {@link #start()} before any WebView is created.
 */
public final class WebResourceServer implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(WebResourceServer.class);

    /**
     * Threads, timeouts, limits and the path hand-off address; start from {@link #defaults()} and add
     * {@link #withPathHandoff} as needed. {@code handlerTimeout} bounds a request's wait for its handler: 504 after it.
     */
    public record Config(int minThreads, int maxThreads, Duration idleTimeout, int maxConcurrentRequests,
                         long maxRequestBodyBytes, Duration stopTimeout, Duration handlerTimeout,
                         String pathHandoffPath) {
        public Config {
            Objects.requireNonNull(handlerTimeout, "handlerTimeout");
            pathHandoffPath = Objects.toString(pathHandoffPath, "");
        }

        public static Config defaults() {
            return new Config(2, 8, Duration.ofSeconds(30), 16, 600L * 1024 * 1024, Duration.ofSeconds(2),
                    Duration.ofSeconds(60), "");
        }

        /**
         * Declares the path hand-off address, a full path starting with {@code /} such as
         * {@code /file/uploads/dropped-files}: only multipart bodies sent there become lists of disk paths.
         * Without it there is no such channel, and dropped files never reach {@code WebResourceRequest.uploadFiles()}.
         */
        public Config withPathHandoff(String path) {
            return new Config(minThreads, maxThreads, idleTimeout, maxConcurrentRequests,
                    maxRequestBodyBytes, stopTimeout, handlerTimeout, path);
        }
    }

    private final WebResourceSchemeRegistry registry;
    private final String publicHost;
    private final Config config;
    private Server server;
    private WebResourceAuth auth;
    private String uploadToken;
    private volatile int port = -1;

    public WebResourceServer(WebResourceSchemeRegistry registry, String publicHost) {
        this(registry, publicHost, Config.defaults());
    }

    /**
     * @param publicHost host of the page origin; must be {@code <label>.localhost}
     *                   ({@link WebResourceAuth#requirePublicHost})
     */
    public WebResourceServer(WebResourceSchemeRegistry registry, String publicHost, Config config) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.publicHost = WebResourceAuth.requirePublicHost(publicHost);
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Returns the server registered with the application, or empty if none is or the application is disposed. */
    public static Optional<WebResourceServer> find(Application application) {
        if (application == null || application.isDisposed()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(application.getService(WebResourceServer.class));
        } catch (RuntimeException notRegistered) {
            return Optional.empty();
        }
    }

    public synchronized void start() {
        if (server != null) {
            throw new IllegalStateException("WebResourceServer already started");
        }
        QueuedThreadPool pool = new QueuedThreadPool(config.maxThreads(), config.minThreads());
        pool.setName("jdesk-web-resources");
        pool.setDaemon(true);
        Server jetty = new Server(pool);
        HttpConfiguration http = new HttpConfiguration();
        http.setSendServerVersion(false);
        http.setSendDateHeader(false);
        // Accept %25 from file names, rejected by default; only safe while each path is decoded exactly once.
        http.setUriCompliance(UriCompliance.DEFAULT.with("jdesk-resources",
                UriCompliance.Violation.AMBIGUOUS_PATH_ENCODING));
        // One acceptor and one selector: the CPU-based defaults would take most of this small pool.
        ServerConnector connector = new ServerConnector(jetty, 1, 1, new HttpConnectionFactory(http));
        connector.setHost(WebResourceAuth.BIND_HOST);
        connector.setPort(0);
        connector.setIdleTimeout(config.idleTimeout().toMillis());
        jetty.addConnector(connector);
        jetty.setStopTimeout(config.stopTimeout().toMillis());

        WebResourceAuth pendingAuth = new WebResourceAuth(publicHost, WebResourceAuth.newToken());
        String pendingUploadToken = WebResourceAuth.newToken();
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new WebResourceServlet(registry, pendingAuth, pendingUploadToken,
                config.maxRequestBodyBytes(), config.handlerTimeout())), "/*");
        QoSHandler qos = new QoSHandler(context);
        qos.setMaxRequestCount(config.maxConcurrentRequests());
        jetty.setHandler(qos);
        try {
            jetty.start();
        } catch (Exception failure) {
            throw new IllegalStateException("Web resource server failed to start", failure);
        }
        // Auth learns the loopback address once bound; requests arriving earlier get a harmless 403.
        port = connector.getLocalPort();
        pendingAuth.bindPort(port);
        auth = pendingAuth;
        uploadToken = pendingUploadToken;
        server = jetty;
        LOG.info("web.resources.started origin={} bind={}", publicBaseUrl(), bindBaseUrl());
    }

    public boolean isRunning() {
        Server current = server;
        return current != null && current.isRunning();
    }

    public int port() {
        return port;
    }

    public String publicHost() {
        return publicHost;
    }

    /** Returns the origin of pages and their relative paths, stable across starts. */
    public String publicBaseUrl() {
        return "http://" + publicHost;
    }

    /** Returns the listening loopback address, whose port changes per start; for proxy rules and debugging. */
    public String bindBaseUrl() {
        if (!isRunning()) {
            throw new IllegalStateException("WebResourceServer is not running");
        }
        return "http://" + WebResourceAuth.BIND_HOST + ":" + port;
    }

    /** Returns the session details; for WebView backends only. */
    public WebResourceSession session() {
        return new WebResourceSession(WebResourceAuth.COOKIE_NAME, publicHost, auth.cookieValue(),
                publicBaseUrl(), bindBaseUrl(), pathHandoffUrl(), uploadToken);
    }

    /** Full URL of the path hand-off address, or an empty string if none was declared. */
    private String pathHandoffUrl() {
        return config.pathHandoffPath().isEmpty() ? "" : publicBaseUrl() + config.pathHandoffPath();
    }

    @Override
    public synchronized void dispose() {
        if (server == null) {
            return;
        }
        try {
            server.stop();
        } catch (Exception failure) {
            LOG.warn("web.resources.stop.failed", failure);
        }
        server = null;
        LOG.info("web.resources.stopped");
    }
}
