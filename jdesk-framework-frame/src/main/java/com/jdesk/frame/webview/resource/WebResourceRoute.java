package com.jdesk.frame.webview.resource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps a resource HTTP path {@code /<name>/<host>/<path>} to the handler URI {@code <name>://<host>/<path>}. Decoded
 * segments are validated, rejecting {@code .}, {@code ..}, backslashes, encoded slashes, control characters and inner
 * empty segments; percent escapes are left for the handler to decode.
 */
public record WebResourceRoute(String name, String host, URI uri) {

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

    /** Returns empty for a path not shaped like a route; throws {@link IllegalArgumentException} for a bad segment. */
    public static Optional<WebResourceRoute> parse(String rawPath, String rawQuery) {
        if (rawPath == null || !rawPath.startsWith("/")) {
            return Optional.empty();
        }
        String[] raw = rawPath.substring(1).split("/", -1);
        if (raw.length < 2 || !NAME.matcher(raw[0]).matches() || raw[1].isEmpty()) {
            return Optional.empty();
        }
        for (int i = 1; i < raw.length; i++) {
            String decoded = percentDecode(raw[i]);
            boolean last = i == raw.length - 1;
            if (decoded.isEmpty() && !last) {
                throw new IllegalArgumentException("Path contains an empty segment.");
            }
            if (".".equals(decoded) || "..".equals(decoded)) {
                throw new IllegalArgumentException("Path contains an invalid segment.");
            }
            for (char c : decoded.toCharArray()) {
                if (c == '\\' || c == '/' || c < 0x20 || c == 0x7f) {
                    throw new IllegalArgumentException("Path contains an illegal character.");
                }
            }
        }
        String host = percentDecode(raw[1]);
        StringBuilder rest = new StringBuilder();
        for (int i = 2; i < raw.length; i++) {
            if (i > 2) {
                rest.append('/');
            }
            rest.append(raw[i]);
        }
        String text = raw[0] + "://" + raw[1] + "/" + rest
                + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery);
        try {
            return Optional.of(new WebResourceRoute(raw[0], host, URI.create(text)));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Path is not a valid resource URI.", malformed);
        }
    }

    /** Maps {@code Sec-Fetch-Dest} to a resource type, OTHER by default. */
    public static WebResourceType resourceTypeOf(String secFetchDest) {
        if (secFetchDest == null) {
            return WebResourceType.OTHER;
        }
        return switch (secFetchDest.trim().toLowerCase(Locale.ROOT)) {
            case "document" -> WebResourceType.MAIN_FRAME;
            case "iframe", "frame", "embed", "object" -> WebResourceType.SUB_FRAME;
            case "script", "worker", "sharedworker", "serviceworker" -> WebResourceType.SCRIPT;
            case "style" -> WebResourceType.STYLESHEET;
            case "image" -> WebResourceType.IMAGE;
            case "font" -> WebResourceType.FONT;
            case "audio", "video", "track" -> WebResourceType.MEDIA;
            case "empty" -> WebResourceType.XHR;
            default -> WebResourceType.OTHER;
        };
    }

    private static String percentDecode(String segment) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(segment.length());
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c != '%') {
                plain.append(c);
                continue;
            }
            flush(plain, out);
            if (i + 2 >= segment.length()) {
                throw new IllegalArgumentException("Path contains a truncated percent escape.");
            }
            int hi = Character.digit(segment.charAt(i + 1), 16);
            int lo = Character.digit(segment.charAt(i + 2), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Path contains an invalid percent escape.");
            }
            out.write((hi << 4) | lo);
            i += 2;
        }
        flush(plain, out);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Path is not valid UTF-8.", failure);
        }
    }

    private static void flush(StringBuilder plain, ByteArrayOutputStream out) {
        if (plain.length() == 0) {
            return;
        }
        byte[] utf8 = plain.toString().getBytes(StandardCharsets.UTF_8);
        out.write(utf8, 0, utf8.length);
        plain.setLength(0);
    }
}
