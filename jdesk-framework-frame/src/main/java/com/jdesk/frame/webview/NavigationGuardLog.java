package com.jdesk.frame.webview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs navigations blocked by the engine-level guard. A hit means something bypassed the page's own guard, so log
 * enough to locate it.
 */
public final class NavigationGuardLog {

    private static final Logger LOG = LoggerFactory.getLogger(NavigationGuardLog.class);

    private NavigationGuardLog() {
    }

    public static void blocked(String backend, String url, String baseline, boolean redirect, boolean popup) {
        String shape = popup ? "popup" : (redirect ? "redirect" : "navigation");
        LOG.warn("webview.navigation.blocked backend={} kind={} url={} baseline={}",
                backend, shape, url, baseline);
    }
}
