package com.jdesk.frame.webview.resource;

import java.util.Objects;

/**
 * One file of a multipart upload: its name and absolute disk path, which Chromium passes instead of the bytes for a
 * file-backed File. The path isn't checked for existence or readability; that is up to the consumer.
 */
public record WebResourceUploadFile(String name, String path) {

    public WebResourceUploadFile {
        name = Objects.requireNonNull(name, "name").trim();
        path = Objects.requireNonNull(path, "path").trim();
    }
}
