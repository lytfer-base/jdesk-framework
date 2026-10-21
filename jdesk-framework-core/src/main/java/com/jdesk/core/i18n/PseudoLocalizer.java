package com.jdesk.core.i18n;

import java.util.List;
import java.util.StringJoiner;

/**
 * Pseudo-localization: replaces letters with accented look-alikes, pads the text by about 40 percent of its letter
 * count and wraps it in brackets, keeping placeholders and literals. If you change the algorithm, update the shared
 * cases in src/test/resources/i18n/pseudo-cases.json that other runtimes follow too.
 */
public final class PseudoLocalizer {
    private static final String PLAIN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String ACCENTED = "áƀçðéƒĝĥíĵķļɱñöþǫŕšţûṽŵẋýžÅƁÇÐÉƑĜĤÎĴĶĻṀÑÖÞǪŔŠŢÛṼŴẊÝŽ";

    private PseudoLocalizer() {
    }

    /** Transforms one message, each plural segment separately; null counts as an empty string. */
    public static String pseudo(String pattern) {
        List<String> segments = MessageFormatter.splitPlural(pattern == null ? "" : pattern);
        StringJoiner joined = new StringJoiner(" | ");
        for (String segment : segments) {
            joined.add(pseudoSegment(segments.size() > 1 ? segment.trim() : segment));
        }
        return joined.toString();
    }

    private static String pseudoSegment(String text) {
        StringBuilder out = new StringBuilder("[");
        int letters = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{') {
                int close = MessageFormatter.findClose(text, i);
                int end = close < 0 ? text.length() : close + 1;
                out.append(text, i, end);
                i = end;
                continue;
            }
            int index = PLAIN.indexOf(c);
            if (index >= 0) {
                out.append(ACCENTED.charAt(index));
                letters++;
            } else {
                out.append(c);
            }
            i++;
        }
        if (letters > 0) {
            out.append(' ').append("~".repeat((letters * 2 + 4) / 5));
        }
        return out.append(']').toString();
    }
}
