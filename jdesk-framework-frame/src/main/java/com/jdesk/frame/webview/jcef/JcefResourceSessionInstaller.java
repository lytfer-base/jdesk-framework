package com.jdesk.frame.webview.jcef;

import com.jdesk.frame.webview.NavigationGuardLog;
import com.jdesk.frame.webview.resource.UploadPathRewrite;
import com.jdesk.frame.webview.resource.http.WebResourceSession;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCommandLine;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;
import org.cef.network.CefPostData;
import org.cef.network.CefPostDataElement;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Vector;

/**
 * Connects the resource server session to JCEF: a command-line switch maps the public host to the loopback port, a
 * session cookie is seeded before the first navigation, and request interception hands dropped files over by path.
 * Each step has its moment: the switch before {@code CefApp} is first created, the cookie after initialization but
 * before the first navigation, and the interceptor with the {@link CefClient}.
 */
final class JcefResourceSessionInstaller {

    private static final Logger LOG = LoggerFactory.getLogger(JcefResourceSessionInstaller.class);

    private JcefResourceSessionInstaller() {
    }

    /** Maps the public host and port to the loopback address, leaving every other host alone. */
    static void applyCommandLine(CefCommandLine commandLine, WebResourceSession session) {
        commandLine.appendSwitchWithValue("host-resolver-rules", session.hostResolverRules());
    }

    /**
     * Seeds the session cookie so the first document navigation passes authentication.
     * {@link CefCookie} has no SameSite field, so the cookie counts as Lax; the server reissues it with
     * {@code SameSite=None} on document responses for sandboxed iframes.
     */
    static void seedSessionCookie(WebResourceSession session) {
        Date now = new Date();
        CefCookie cookie = new CefCookie(session.cookieName(), session.cookieValue(),
                session.cookieDomain(), "/", true, true, now, now, false, null);
        boolean seeded = CefCookieManager.getGlobalManager().setCookie(session.publicBaseUrl() + "/", cookie);
        LOG.info("web.resources.session.cookie origin={} seeded={}", session.publicBaseUrl(), seeded);
    }

    /**
     * Installs one request handler for the navigation guard, upload rewriting and render process exits; without a
     * session there is no upload rewriting.
     * Don't call addRequestHandler again: CefClient keeps one per kind, so a second call replaces this one.
     */
    static void installRequestHandler(CefClient client, WebResourceSession session,
                                      java.util.function.Function<CefBrowser, JcefWebView> views) {
        client.addRequestHandler(new UploadRewriteRequestHandler(session, views));
        if (session != null) {
            LOG.info("web.resources.session.installed origin={} bind={}",
                    session.publicBaseUrl(), session.bindBaseUrl());
        } else {
            LOG.info("web.resources.session.installed session=none");
        }
    }

    private static final class UploadRewriteRequestHandler extends CefRequestHandlerAdapter {
        private final CefResourceRequestHandler resourceHandler;
        private final java.util.function.Function<CefBrowser, JcefWebView> views;

        private UploadRewriteRequestHandler(WebResourceSession session,
                                            java.util.function.Function<CefBrowser, JcefWebView> views) {
            this.resourceHandler = session == null ? null : new UploadRewriteResourceHandler(session);
            this.views = Objects.requireNonNull(views, "views");
        }

        /** Returns the upload rewriter, or null for default handling when the resource server isn't running. */
        @Override
        public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame,
                                                                   CefRequest request, boolean isNavigation,
                                                                   boolean isDownload, String requestInitiator,
                                                                   BoolRef disableDefaultHandling) {
            return resourceHandler;
        }

        /** Engine-level navigation guard; returns true to cancel the navigation. */
        @Override
        public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request,
                                      boolean userGesture, boolean isRedirect) {
            if (frame == null || !frame.isMain()) {
                return false;
            }
            JcefWebView view = views.apply(browser);
            if (view == null) {
                return false;
            }
            String url = request == null ? null : request.getURL();
            String baseline = view.navigationBaseline().origin();
            if (view.navigationPolicy().allowsMainFrame(url, baseline)) {
                return false;
            }
            NavigationGuardLog.blocked("jcef", url, baseline, isRedirect, false);
            return true;
        }

        /** Logs a lost render process: the page goes blank and the bridge stops answering, with no recovery. */
        @Override
        public void onRenderProcessTerminated(CefBrowser browser, CefRequestHandler.TerminationStatus status) {
            LOG.warn("jcef.renderProcess.terminated status={} url={}", status, browser == null ? null : browser.getURL());
        }
    }

    private static final class UploadRewriteResourceHandler extends CefResourceRequestHandlerAdapter {
        private final WebResourceSession session;

        private UploadRewriteResourceHandler(WebResourceSession session) {
            this.session = session;
        }

        @Override
        public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame frame, CefRequest request) {
            try {
                rewriteUpload(request);
            } catch (RuntimeException failure) {
                // Rewriting is an optimization; on failure the real multipart body goes to the server.
                LOG.debug("Upload rewrite skipped", failure);
            }
            return false;
        }

        private void rewriteUpload(CefRequest request) {
            String url = request.getURL();
            if (url == null || !url.equals(session.pathHandoffUrl())
                    || !"POST".equalsIgnoreCase(request.getMethod())) {
                return;
            }
            // Check the boundary before reading elements, so a non-multipart body is never copied into a String.
            String contentType = request.getHeaderByName("Content-Type");
            if (JcefMultipartSegments.boundaryOf(contentType).isEmpty()) {
                return;
            }
            CefPostData postData = request.getPostData();
            if (postData == null) {
                return;
            }
            Optional<List<UploadPathRewrite.Segment>> segments = JcefMultipartSegments.parse(
                    contentType, readElements(postData));
            if (segments.isEmpty()) {
                return;
            }
            Optional<List<Map.Entry<String, String>>> rewritten =
                    UploadPathRewrite.rewrite(url, session.pathHandoffUrl(), session.uploadToken(), segments.get());
            if (rewritten.isEmpty()) {
                return;
            }
            byte[] body = urlEncoded(rewritten.get()).getBytes(StandardCharsets.UTF_8);
            CefPostDataElement element = CefPostDataElement.create();
            element.setToBytes(body.length, body);
            CefPostData replacement = CefPostData.create();
            replacement.addElement(element);
            request.setPostData(replacement);
            request.setHeaderByName("Content-Type", "application/x-www-form-urlencoded", true);
        }

        /**
         * Reads file elements as disk paths and byte elements whole, since those hold part headers and fields.
         * Call it only for a multipart body: in any other request the byte elements are the content itself.
         */
        private static List<JcefMultipartSegments.Element> readElements(CefPostData postData) {
            Vector<CefPostDataElement> raw = new Vector<>();
            postData.getElements(raw);
            List<JcefMultipartSegments.Element> elements = new ArrayList<>(raw.size());
            for (CefPostDataElement element : raw) {
                if (element.getType() == CefPostDataElement.Type.PDE_TYPE_FILE) {
                    elements.add(JcefMultipartSegments.Element.file(element.getFile()));
                    continue;
                }
                if (element.getType() != CefPostDataElement.Type.PDE_TYPE_BYTES) {
                    elements.add(JcefMultipartSegments.Element.bytes(""));
                    continue;
                }
                int size = element.getBytesCount();
                byte[] bytes = new byte[size];
                element.getBytes(size, bytes);
                elements.add(JcefMultipartSegments.Element.bytes(new String(bytes, StandardCharsets.UTF_8)));
            }
            return elements;
        }

        private static String urlEncoded(List<Map.Entry<String, String>> pairs) {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> pair : pairs) {
                if (body.length() > 0) {
                    body.append('&');
                }
                body.append(java.net.URLEncoder.encode(pair.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(java.net.URLEncoder.encode(pair.getValue(), StandardCharsets.UTF_8));
            }
            return body.toString();
        }
    }
}
