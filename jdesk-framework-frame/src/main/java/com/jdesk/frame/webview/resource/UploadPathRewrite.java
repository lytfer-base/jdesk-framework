package com.jdesk.frame.webview.resource;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rewrites a multipart body sent to the path hand-off address into a {@link WebResourceUploadForm} form when every
 * file part is file-backed: string fields stay, file parts become a path list, and the hand-off token is added. Any
 * other request is left alone, so the real multipart body reaches the server.
 */
public final class UploadPathRewrite {

    /** One part; a file part has a null {@code stringValue}, and a null or empty {@code filePath} if pathless. */
    public record Segment(String key, String stringValue, String fileName, String filePath) {
    }

    private UploadPathRewrite() {
    }

    /**
     * Returns the rewritten form fields, or empty to leave the request alone.
     *
     * @param handoffUrl  full URL of the path hand-off address ({@code WebResourceSession.pathHandoffUrl()}); empty
     *                    means there is no such channel and nothing is rewritten
     * @param uploadToken hand-off token ({@code WebResourceSession.uploadToken()}); empty means no rewriting
     */
    public static Optional<List<Map.Entry<String, String>>> rewrite(String url, String handoffUrl, String uploadToken,
                                                                    List<Segment> segments) {
        if (url == null || handoffUrl == null || handoffUrl.isBlank() || !url.equals(handoffUrl)
                || uploadToken == null || uploadToken.isBlank()) {
            return Optional.empty();
        }
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        List<WebResourceUploadFile> files = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.stringValue() != null) {
                if (WebResourceUploadForm.FIELD.equals(segment.key())
                        || WebResourceUploadForm.TOKEN_FIELD.equals(segment.key())) {
                    return Optional.empty();
                }
                pairs.add(new AbstractMap.SimpleImmutableEntry<>(segment.key(), segment.stringValue()));
                continue;
            }
            if (segment.filePath() == null || segment.filePath().isBlank()) {
                return Optional.empty();
            }
            files.add(new WebResourceUploadFile(segment.fileName() == null ? "" : segment.fileName(), segment.filePath()));
        }
        if (files.isEmpty()) {
            return Optional.empty();
        }
        pairs.add(new AbstractMap.SimpleImmutableEntry<>(WebResourceUploadForm.FIELD, WebResourceUploadForm.encode(files)));
        pairs.add(new AbstractMap.SimpleImmutableEntry<>(WebResourceUploadForm.TOKEN_FIELD, uploadToken));
        return Optional.of(List.copyOf(pairs));
    }
}
