package com.jdesk.frame.webview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests when the navigation baseline locks: not on the bootstrap document, but on the first main-frame commit after a
 * content load, and never again after that.
 */
final class NavigationBaselineTest {

    private static final String APP = "http://myapp.localhost";

    /** JCEF calls createBrowser("about:blank") before loadUrl; locking on that page would block the real surface. */
    @Test
    void bootstrapCommitBeforeAnyContentLoadDoesNotLock() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.documentCommitted("about:blank");

        assertFalse(baseline.isLocked());
        assertNull(baseline.origin());
    }

    @Test
    void firstCommitAfterContentLoadLocksToItsOrigin() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.contentLoadRequested();
        baseline.documentCommitted(APP + "/app/myapp/projects/v1/index.html");

        assertTrue(baseline.isLocked());
        assertEquals(APP, baseline.origin());
    }

    @Test
    void baselineNeverChangesAfterLocking() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.contentLoadRequested();
        baseline.documentCommitted(APP + "/a.html");
        baseline.documentCommitted("https://example.com/evil");
        baseline.contentLoadRequested();

        assertEquals(APP, baseline.origin());
    }

    /** A document from loadHtml has no origin, so it locks as opaque and every later navigation is refused. */
    @Test
    void documentWithoutOriginLocksOpaque() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.contentLoadRequested();
        baseline.documentCommitted("data:text/html,<p>fallback</p>");

        assertTrue(baseline.isLocked());
        assertEquals("", baseline.origin());
    }

    /** The bootstrap page is ignored even if it commits after the content load request; JCEF doesn't order it. */
    @Test
    void bootstrapUrlIsIgnoredEvenAfterContentLoadRequested() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.contentLoadRequested();
        baseline.documentCommitted("about:blank");

        assertFalse(baseline.isLocked());

        baseline.documentCommitted(APP + "/a.html");
        assertEquals(APP, baseline.origin());
    }

    /** A mixed-case bootstrap URL is recognized too, not only a lowercase about:blank. */
    @Test
    void mixedCaseBootstrapUrlIsIgnored() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.contentLoadRequested();
        baseline.documentCommitted("About:Blank");

        assertFalse(baseline.isLocked());
    }

    /** Null, empty and blank URLs count as bootstrap: no lock and no exception. */
    @Test
    void blankCommitsAreIgnoredWithoutThrowing() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.contentLoadRequested();

        assertDoesNotThrow(() -> baseline.documentCommitted(null));
        assertDoesNotThrow(() -> baseline.documentCommitted(""));
        assertDoesNotThrow(() -> baseline.documentCommitted("   "));

        assertFalse(baseline.isLocked());
        assertNull(baseline.origin());
    }

    /** Both backends load fallback HTML through a data: URL, which must lock as opaque. */
    @Test
    void fallbackHtmlDataUrlLocksOpaque() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.contentLoadRequested();
        baseline.documentCommitted("data:text/html;charset=utf-8;base64,PGh0bWw+PC9odG1sPg==");

        assertTrue(baseline.isLocked());
        assertEquals("", baseline.origin());
    }

    @Test
    void usableFailedUrlLocksToItsOrigin() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.contentLoadRequested();
        baseline.documentFailed(APP + "/app/myapp/projects/v1/index.html");

        assertTrue(baseline.isLocked());
        assertEquals(APP, baseline.origin());
    }

    /** A failure with no usable URL after a content load request locks as opaque, refusing everything. */
    @Test
    void nullOrBlankFailedUrlLocksOpaque() {
        NavigationBaseline nullCase = new NavigationBaseline();
        nullCase.contentLoadRequested();
        nullCase.documentFailed(null);
        assertTrue(nullCase.isLocked());
        assertEquals("", nullCase.origin());

        NavigationBaseline blankCase = new NavigationBaseline();
        blankCase.contentLoadRequested();
        blankCase.documentFailed("   ");
        assertTrue(blankCase.isLocked());
        assertEquals("", blankCase.origin());
    }

    @Test
    void aboutSchemeFailedUrlDoesNotLock() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.contentLoadRequested();
        baseline.documentFailed("about:blank");

        assertFalse(baseline.isLocked());
        assertNull(baseline.origin());
    }

    /**
     * A null or blank failed URL before any content load request must not lock, or the real content would be refused
     * as cross-origin and the WebView would stay blank.
     */
    @Test
    void failedUrlBeforeContentLoadRequestedDoesNotLock() {
        NavigationBaseline nullCase = new NavigationBaseline();
        nullCase.documentFailed(null);
        assertFalse(nullCase.isLocked());
        assertNull(nullCase.origin());

        NavigationBaseline blankCase = new NavigationBaseline();
        blankCase.documentFailed("   ");
        assertFalse(blankCase.isLocked());
        assertNull(blankCase.origin());

        nullCase.contentLoadRequested();
        nullCase.documentCommitted(APP + "/a.html");
        assertEquals(APP, nullCase.origin());
    }

    @Test
    void failureAfterLockingDoesNotChangeBaseline() {
        NavigationBaseline baseline = new NavigationBaseline();
        baseline.contentLoadRequested();
        baseline.documentCommitted(APP + "/a.html");

        baseline.documentFailed("https://example.com/evil");

        assertEquals(APP, baseline.origin());
    }
}
