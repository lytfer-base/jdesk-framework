package com.jdesk.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HistoryPolicyTest {

    @Test
    void defaultsMatchSpecNumbers() {
        HistoryPolicy policy = HistoryPolicy.defaults();
        assertEquals(Duration.ofMinutes(5), policy.foldWindow());
        assertEquals(Duration.ofSeconds(60), policy.externalIdleGap());
        assertEquals(20L * 1024 * 1024, policy.maxObjectBytes());
        assertEquals(64L * 1024, policy.eagerBaselineMaxBytes());
        assertEquals(1024L * 1024 * 1024, policy.libraryCapBytes());
        assertEquals(Duration.ofDays(30), policy.deletedRetention());
    }

    @Test
    void textNameMatchesExtensionIgnoringCaseAndDirectories() {
        HistoryPolicy policy = HistoryPolicy.defaults();
        assertTrue(policy.isTextName("notes/a/b.MD"));
        assertTrue(policy.isTextName("x.txt"));
        assertFalse(policy.isTextName("assets/img/x.png"));
        assertFalse(policy.isTextName("README"));
        assertFalse(policy.isTextName(".gitignore"));
        assertFalse(policy.isTextName(null));
    }

    @Test
    void keyRejectsBlankParts() {
        assertThrows(IllegalArgumentException.class, () -> new HistoryKey("file", " "));
        assertEquals("rec-1", HistoryKey.of("record", " rec-1 ").id());
    }
}
