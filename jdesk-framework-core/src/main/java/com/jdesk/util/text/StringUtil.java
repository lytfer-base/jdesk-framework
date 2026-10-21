package com.jdesk.util.text;

import java.util.*;
import java.util.regex.Pattern;

/**
 * String utility functions.
 * Inspired by IntelliJ Platform's {@code StringUtil}.
 */
public final class StringUtil {

    private StringUtil() {}

    private static final Pattern LINE_BREAK = Pattern.compile("\\r?\\n");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // --- Null/Empty checks ---

    public static boolean isEmpty(CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    public static boolean isNotEmpty(CharSequence cs) {
        return cs != null && cs.length() > 0;
    }

    public static boolean isEmptyOrSpaces(CharSequence cs) {
        return cs == null || cs.toString().isBlank();
    }

    // --- Default if empty ---

    public static String defaultIfEmpty(String value, String defaultValue) {
        return isEmpty(value) ? defaultValue : value;
    }

    // --- Split / Join ---

    public static List<String> split(String s, String separator) {
        return split(s, separator, true);
    }

    public static List<String> split(String s, String separator, boolean trim) {
        if (isEmpty(s)) return Collections.emptyList();
        String[] parts = s.split(Pattern.quote(separator));
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String t = trim ? part.trim() : part;
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    public static List<String> splitByLines(String s) {
        if (isEmpty(s)) return Collections.emptyList();
        return Arrays.asList(LINE_BREAK.split(s));
    }

    public static String join(Iterable<?> items, String separator) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object item : items) {
            if (!first) sb.append(separator);
            sb.append(item);
            first = false;
        }
        return sb.toString();
    }

    // --- Capitalize / Decapitalize ---

    public static String capitalize(String s) {
        if (isEmpty(s)) return s;
        if (s.length() == 1) return s.toUpperCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String decapitalize(String s) {
        if (isEmpty(s)) return s;
        if (s.length() == 1) return s.toLowerCase();
        // Don't decapitalize if second char is uppercase (likely an acronym)
        if (Character.isUpperCase(s.charAt(1))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // --- Truncate ---

    public static String truncate(String s, int maxLength) {
        if (s == null || s.length() <= maxLength) return s;
        return s.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    // --- Contains / StartsWith / EndsWith (ignore case) ---

    public static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    // --- Format ---

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), unit);
    }

    public static String formatDuration(long millis) {
        if (millis < 1000) return millis + "ms";
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return minutes + "m " + seconds + "s";
    }

    // --- Escape / Unescape ---

    public static String escapeXml(String s) {
        if (isEmpty(s)) return s;
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // --- Words ---

    public static String wordsToBeginFromUpperCase(String s) {
        if (isEmpty(s)) return s;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                result.append(' ');
            }
            result.append(c);
        }
        return result.toString();
    }
}
