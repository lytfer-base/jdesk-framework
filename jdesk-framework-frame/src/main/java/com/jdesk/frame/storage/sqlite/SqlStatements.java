package com.jdesk.frame.storage.sqlite;

import java.util.regex.Pattern;

/**
 * Tells whether SQL text holds more than one statement, since a prepared statement silently runs only the first.
 * A CREATE TRIGGER counts as one statement despite the semicolons in its body.
 */
final class SqlStatements {

    private static final Pattern TRIGGER = Pattern.compile("(?is)create\\s+(temp\\s+|temporary\\s+)?trigger\\b.*");

    private SqlStatements() {
    }

    /**
     * Returns true if anything but whitespace, comments and semicolons follows the first top-level semicolon;
     * semicolons inside quotes, bracketed identifiers and comments don't count.
     */
    static boolean hasMoreThanOne(String sql) {
        if (sql == null) {
            return false;
        }
        int start = skipNoise(sql, 0, false);
        if (TRIGGER.matcher(sql.substring(start)).matches()) {
            return false;
        }
        int i = start;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
            } else if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                i = end < 0 ? sql.length() : end + 1;
            } else if (startsComment(sql, i)) {
                i = skipComment(sql, i);
            } else if (c == ';') {
                return skipNoise(sql, i + 1, true) < sql.length();
            } else {
                i++;
            }
        }
        return false;
    }

    /** Skips whitespace and comments from {@code i}, and semicolons if {@code semicolons}; returns the next index. */
    private static int skipNoise(String sql, int i, boolean semicolons) {
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || (semicolons && c == ';')) {
                i++;
            } else if (startsComment(sql, i)) {
                i = skipComment(sql, i);
            } else {
                break;
            }
        }
        return i;
    }

    private static boolean startsComment(String sql, int i) {
        if (i + 1 >= sql.length()) {
            return false;
        }
        char c = sql.charAt(i);
        char next = sql.charAt(i + 1);
        return (c == '-' && next == '-') || (c == '/' && next == '*');
    }

    private static int skipComment(String sql, int i) {
        if (sql.charAt(i) == '-') {
            int end = sql.indexOf('\n', i);
            return end < 0 ? sql.length() : end + 1;
        }
        int end = sql.indexOf("*/", i + 2);
        return end < 0 ? sql.length() : end + 2;
    }

    /** Skips a quoted run; a doubled quote is an escape. */
    private static int skipQuoted(String sql, int i, char quote) {
        int j = i + 1;
        while (j < sql.length()) {
            if (sql.charAt(j) == quote) {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return sql.length();
    }
}
