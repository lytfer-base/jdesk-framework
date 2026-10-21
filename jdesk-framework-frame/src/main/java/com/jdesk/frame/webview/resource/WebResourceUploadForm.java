package com.jdesk.frame.webview.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Carrier of the upload path channel: the {@value #FIELD} field of a url-encoded form, holding
 * {@code [{"name","path"}]}, plus the hand-off token field {@value #TOKEN_FIELD}. Browser backends rewrite file-backed
 * multipart bodies into it, and the server decodes {@link WebResourceUploadFile}s only if the token matches.
 */
public final class WebResourceUploadForm {

    public static final String FIELD = "jdesk.uploadFiles";
    /** Hand-off token the backend adds when rewriting; pages can't get it, so path lists they build don't count. */
    public static final String TOKEN_FIELD = "jdesk.uploadToken";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebResourceUploadForm() {
    }

    public static String encode(List<WebResourceUploadFile> files) {
        ArrayNode array = MAPPER.createArrayNode();
        for (WebResourceUploadFile file : files) {
            ObjectNode node = array.addObject();
            node.put("name", file.name());
            node.put("path", file.path());
        }
        return array.toString();
    }

    /**
     * Decodes the listed files; returns an empty list unless the body is a url-encoded form with each field exactly
     * once, a token equal to {@code expectedToken}, and valid encoding and JSON.
     */
    public static List<WebResourceUploadFile> decode(String contentType, byte[] body, String expectedToken) {
        if (contentType == null || body == null || expectedToken == null || expectedToken.isEmpty()
                || !contentType.trim().toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            return List.of();
        }
        try {
            String listed = null;
            String token = null;
            for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = pair.substring(eq + 1);
                if (FIELD.equals(key)) {
                    if (listed != null) {
                        return List.of();
                    }
                    listed = URLDecoder.decode(value, StandardCharsets.UTF_8);
                } else if (TOKEN_FIELD.equals(key)) {
                    if (token != null) {
                        return List.of();
                    }
                    token = URLDecoder.decode(value, StandardCharsets.UTF_8);
                }
            }
            if (listed == null || token == null || !MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8))) {
                return List.of();
            }
            JsonNode array = MAPPER.readTree(listed);
            if (array == null || !array.isArray()) {
                return List.of();
            }
            List<WebResourceUploadFile> files = new ArrayList<>();
            for (JsonNode node : array) {
                String name = node.path("name").asText("");
                String path = node.path("path").asText("");
                if (!name.isBlank() && !path.isBlank()) {
                    files.add(new WebResourceUploadFile(name, path));
                }
            }
            return List.copyOf(files);
        } catch (Exception malformed) {
            return List.of();
        }
    }

    /** Removes {@value #TOKEN_FIELD} from the form, keeping the other fields; returns the body as is without it. */
    public static byte[] withoutToken(byte[] body) {
        if (body == null) {
            return null;
        }
        StringJoiner kept = new StringJoiner("&");
        boolean dropped = false;
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&", -1)) {
            if (isTokenPair(pair)) {
                dropped = true;
            } else {
                kept.add(pair);
            }
        }
        return dropped ? kept.toString().getBytes(StandardCharsets.UTF_8) : body;
    }

    private static boolean isTokenPair(String pair) {
        int eq = pair.indexOf('=');
        try {
            return TOKEN_FIELD.equals(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
