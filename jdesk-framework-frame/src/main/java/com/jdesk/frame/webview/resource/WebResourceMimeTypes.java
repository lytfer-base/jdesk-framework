package com.jdesk.frame.webview.resource;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Small MIME resolver for WebView resources.
 */
public final class WebResourceMimeTypes {

    private WebResourceMimeTypes() {
    }

    public static String forPath(Path path) {
        String fileName = path == null || path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return switch (extension(fileName)) {
            case "html", "htm" -> "text/html; charset=UTF-8";
            case "js", "mjs" -> "application/javascript; charset=UTF-8";
            case "css" -> "text/css; charset=UTF-8";
            case "json", "map" -> "application/json; charset=UTF-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            // instantiateStreaming needs this type; octet-stream falls back to slower instantiation with a warning.
            case "wasm" -> "application/wasm";
            default -> "application/octet-stream";
        };
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1);
    }
}
