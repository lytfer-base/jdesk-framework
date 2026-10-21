package com.jdesk.core.history;

/** Result of a capture; if nothing was captured, {@code skippedReason} is unchanged, has-head, too-large or failed. */
public record CaptureResult(boolean captured, String contentHash, Long versionId, String skippedReason) {

    public static CaptureResult captured(String contentHash, long versionId) {
        return new CaptureResult(true, contentHash, versionId, null);
    }

    public static CaptureResult skipped(String reason) {
        return new CaptureResult(false, null, null, reason);
    }

    public static CaptureResult skipped(String reason, String contentHash) {
        return new CaptureResult(false, contentHash, null, reason);
    }
}
