package com.jdesk.core.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Formats a subset of the message syntax: {@code {name}} placeholders, {@code {'x'}} literals and plural segments
 * chosen by {@code count} with English rules. The output must match the other runtimes that share
 * src/test/resources/i18n/format-cases.json.
 */
public final class MessageFormatter {
    private MessageFormatter() {
    }

    /**
     * Formats {@code pattern}; a missing parameter keeps its placeholder, and a plural message without a count takes
     * the last segment.
     */
    public static String format(String pattern, Map<String, ?> params) {
        if (pattern == null) {
            return "";
        }
        Map<String, ?> values = params == null ? Map.of() : params;
        List<String> segments = splitPlural(pattern);
        String chosen = segments.size() == 1
                ? segments.get(0)
                : segments.get(pluralIndex(values.get("count"), segments.size())).trim();
        return interpolate(chosen, values);
    }

    static List<String> splitPlural(String pattern) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{') {
                int close = findClose(pattern, i);
                int end = close < 0 ? pattern.length() : close + 1;
                current.append(pattern, i, end);
                i = end;
                continue;
            }
            if (c == '|') {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
            i++;
        }
        segments.add(current.toString());
        return segments;
    }

    static int findClose(String text, int open) {
        boolean inQuote = false;
        for (int j = open + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (c == '}' && !inQuote) {
                return j;
            }
        }
        return -1;
    }

    private static int pluralIndex(Object count, int choices) {
        if (!(count instanceof Number number)) {
            return choices - 1;
        }
        double n = Math.abs(number.doubleValue());
        if (choices == 2) {
            return n == 1 ? 0 : 1;
        }
        return (int) Math.min(n, Math.min(2, choices - 1));
    }

    private static String interpolate(String text, Map<String, ?> values) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = findClose(text, i);
            if (close < 0) {
                out.append(text, i, text.length());
                break;
            }
            String body = text.substring(i + 1, close);
            if (body.length() >= 2 && body.startsWith("'") && body.endsWith("'")) {
                out.append(body, 1, body.length() - 1);
            } else if (values.containsKey(body)) {
                out.append(values.get(body));
            } else {
                out.append(text, i, close + 1);
            }
            i = close + 1;
        }
        return out.toString();
    }
}
