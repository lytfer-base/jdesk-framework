package com.jdesk.frame.webview.jcef;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A helper is orphaned only if it uses the same executable and has no living parent. Either check alone would also
 * match other apps sharing the JetBrains Runtime, such as IDEA.
 */
final class JcefOrphanReaperTest {

    private static final String HELPER = "D:/jbr/bin/jcef_helper.exe".replace('/', '\\');

    @Test
    void reapsHelperWithoutLivingParent() {
        assertTrue(JcefOrphanReaper.isOrphan(HELPER,
                new JcefOrphanReaper.Candidate(1234, HELPER, false)));
    }

    @Test
    void keepsHelperThatStillHasALivingParent() {
        assertFalse(JcefOrphanReaper.isOrphan(HELPER,
                        new JcefOrphanReaper.Candidate(1234, HELPER, true)),
                "a living parent owns it; likely IDEA's helper");
    }

    @Test
    void keepsOrphanFromAnotherRuntime() {
        assertFalse(JcefOrphanReaper.isOrphan(HELPER,
                        new JcefOrphanReaper.Candidate(1234,
                                "C:/other/bin/jcef_helper.exe".replace('/', '\\'), false)),
                "orphans of another runtime aren't ours");
    }

    @Test
    void toleratesProcessesWithoutAReadableCommand() {
        assertFalse(JcefOrphanReaper.isOrphan(HELPER,
                new JcefOrphanReaper.Candidate(1234, null, false)));
    }

    @Test
    void matchesHelperPathCaseInsensitively() {
        assertTrue(JcefOrphanReaper.isOrphan(HELPER,
                        new JcefOrphanReaper.Candidate(1234, HELPER.toUpperCase(), false)),
                "Windows paths are case-insensitive");
    }
}
