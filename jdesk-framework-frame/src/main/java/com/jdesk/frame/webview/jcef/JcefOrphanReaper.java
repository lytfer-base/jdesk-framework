package com.jdesk.frame.webview.jcef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Kills CEF helper processes orphaned by an earlier crash or forced kill; a normal exit leaves none, since
 * {@code JcefWebViewService} waits for {@code TERMINATED}.
 */
final class JcefOrphanReaper {

    private static final Logger LOG = LoggerFactory.getLogger(JcefOrphanReaper.class);

    private static final long EXIT_WAIT_SECONDS = 3L;

    /** What the orphan test needs to know about a process. */
    record Candidate(long pid, String command, boolean hasLivingParent) {
    }

    private JcefOrphanReaper() {
    }

    /**
     * Returns whether the process runs our helper and has no living parent.
     * Must check both: other applications on the same JetBrains Runtime, IDEA included, run the same helper.
     */
    static boolean isOrphan(String helperPath, Candidate candidate) {
        return !candidate.hasLivingParent()
                && candidate.command() != null
                && candidate.command().toLowerCase(Locale.ROOT).equals(helperPath.toLowerCase(Locale.ROOT));
    }

    /** Call before creating {@code CefApp}, while this process has no helpers of its own. */
    static void reapOrphans() {
        Optional<Path> helper = JcefRuntime.helper();
        if (helper.isEmpty()) {
            return;
        }
        String helperPath = helper.get().toString();
        List<ProcessHandle> orphans = ProcessHandle.allProcesses()
                .filter(handle -> isOrphan(helperPath, toCandidate(handle)))
                .toList();
        if (orphans.isEmpty()) {
            return;
        }
        LOG.info("jcef.orphan.reap count={} helper={}", orphans.size(), helperPath);
        for (ProcessHandle orphan : orphans) {
            LOG.info("jcef.orphan.kill pid={}", orphan.pid());
            orphan.destroyForcibly();
        }
        for (ProcessHandle orphan : orphans) {
            try {
                orphan.onExit().get(EXIT_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception timeoutOrFailure) {
                LOG.warn("jcef.orphan.stillAlive pid={}", orphan.pid());
            }
        }
    }

    private static Candidate toCandidate(ProcessHandle handle) {
        return new Candidate(handle.pid(), handle.info().command().orElse(null), handle.parent().isPresent());
    }
}
