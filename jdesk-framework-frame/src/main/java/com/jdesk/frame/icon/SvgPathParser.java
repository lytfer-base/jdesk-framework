package com.jdesk.frame.icon;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses SVG path data (the {@code d} attribute) into a Java2D {@link Path2D}. Supports move, line, horizontal and
 * vertical line, cubic and quadratic Bezier (smooth forms included) and close commands; an arc is drawn as a
 * straight line to its end point.
 */
final class SvgPathParser {

    private SvgPathParser() {
    }

    static Path2D parse(String data, int windingRule) {
        Path2D.Double path = new Path2D.Double(windingRule);
        if (data == null || data.isBlank()) {
            return path;
        }

        TokenStream tokens = new TokenStream(data);
        char command = 'M';
        double x = 0;
        double y = 0;
        double subX = 0;
        double subY = 0;
        double lastCubicX = 0;
        double lastCubicY = 0;
        double lastQuadX = 0;
        double lastQuadY = 0;
        boolean hasLastCubic = false;
        boolean hasLastQuad = false;

        while (tokens.hasNext()) {
            int start = tokens.position();
            if (tokens.peekCommand()) {
                command = tokens.nextCommand();
            }

            switch (command) {
                case 'M', 'm' -> {
                    boolean relative = command == 'm';
                    boolean first = true;
                    while (tokens.hasNumberPair()) {
                        double nx = tokens.nextNumber();
                        double ny = tokens.nextNumber();
                        if (relative) {
                            nx += x;
                            ny += y;
                        }
                        if (first) {
                            path.moveTo(nx, ny);
                            subX = nx;
                            subY = ny;
                            first = false;
                        } else {
                            path.lineTo(nx, ny);
                        }
                        x = nx;
                        y = ny;
                    }
                    command = relative ? 'l' : 'L';
                    hasLastCubic = false;
                    hasLastQuad = false;
                }
                case 'L', 'l' -> {
                    boolean relative = command == 'l';
                    while (tokens.hasNumberPair()) {
                        double nx = tokens.nextNumber();
                        double ny = tokens.nextNumber();
                        if (relative) {
                            nx += x;
                            ny += y;
                        }
                        path.lineTo(nx, ny);
                        x = nx;
                        y = ny;
                    }
                    hasLastCubic = false;
                    hasLastQuad = false;
                }
                case 'H', 'h' -> {
                    boolean relative = command == 'h';
                    while (tokens.hasNumber()) {
                        double nx = tokens.nextNumber();
                        if (relative) {
                            nx += x;
                        }
                        path.lineTo(nx, y);
                        x = nx;
                    }
                    hasLastCubic = false;
                    hasLastQuad = false;
                }
                case 'V', 'v' -> {
                    boolean relative = command == 'v';
                    while (tokens.hasNumber()) {
                        double ny = tokens.nextNumber();
                        if (relative) {
                            ny += y;
                        }
                        path.lineTo(x, ny);
                        y = ny;
                    }
                    hasLastCubic = false;
                    hasLastQuad = false;
                }
                case 'C', 'c' -> {
                    boolean relative = command == 'c';
                    while (tokens.remainingNumbersBeforeCommand() >= 6) {
                        double x1 = tokens.nextNumber();
                        double y1 = tokens.nextNumber();
                        double x2 = tokens.nextNumber();
                        double y2 = tokens.nextNumber();
                        double nx = tokens.nextNumber();
                        double ny = tokens.nextNumber();
                        if (relative) {
                            x1 += x;
                            y1 += y;
                            x2 += x;
                            y2 += y;
                            nx += x;
                            ny += y;
                        }
                        path.curveTo(x1, y1, x2, y2, nx, ny);
                        lastCubicX = x2;
                        lastCubicY = y2;
                        x = nx;
                        y = ny;
                        hasLastCubic = true;
                        hasLastQuad = false;
                    }
                }
                case 'S', 's' -> {
                    boolean relative = command == 's';
                    while (tokens.remainingNumbersBeforeCommand() >= 4) {
                        double x1 = hasLastCubic ? 2 * x - lastCubicX : x;
                        double y1 = hasLastCubic ? 2 * y - lastCubicY : y;
                        double x2 = tokens.nextNumber();
                        double y2 = tokens.nextNumber();
                        double nx = tokens.nextNumber();
                        double ny = tokens.nextNumber();
                        if (relative) {
                            x2 += x;
                            y2 += y;
                            nx += x;
                            ny += y;
                        }
                        path.curveTo(x1, y1, x2, y2, nx, ny);
                        lastCubicX = x2;
                        lastCubicY = y2;
                        x = nx;
                        y = ny;
                        hasLastCubic = true;
                        hasLastQuad = false;
                    }
                }
                case 'Q', 'q' -> {
                    boolean relative = command == 'q';
                    while (tokens.remainingNumbersBeforeCommand() >= 4) {
                        double x1 = tokens.nextNumber();
                        double y1 = tokens.nextNumber();
                        double nx = tokens.nextNumber();
                        double ny = tokens.nextNumber();
                        if (relative) {
                            x1 += x;
                            y1 += y;
                            nx += x;
                            ny += y;
                        }
                        path.quadTo(x1, y1, nx, ny);
                        lastQuadX = x1;
                        lastQuadY = y1;
                        x = nx;
                        y = ny;
                        hasLastQuad = true;
                        hasLastCubic = false;
                    }
                }
                case 'T', 't' -> {
                    boolean relative = command == 't';
                    while (tokens.hasNumberPair()) {
                        double x1 = hasLastQuad ? 2 * x - lastQuadX : x;
                        double y1 = hasLastQuad ? 2 * y - lastQuadY : y;
                        double nx = tokens.nextNumber();
                        double ny = tokens.nextNumber();
                        if (relative) {
                            nx += x;
                            ny += y;
                        }
                        path.quadTo(x1, y1, nx, ny);
                        lastQuadX = x1;
                        lastQuadY = y1;
                        x = nx;
                        y = ny;
                        hasLastQuad = true;
                        hasLastCubic = false;
                    }
                }
                case 'A', 'a' -> {
                    boolean relative = command == 'a';
                    double[] arc;
                    while ((arc = tokens.nextArc()) != null) {
                        double nx = arc[5];
                        double ny = arc[6];
                        if (relative) {
                            nx += x;
                            ny += y;
                        }
                        path.lineTo(nx, ny);
                        x = nx;
                        y = ny;
                    }
                    hasLastCubic = false;
                    hasLastQuad = false;
                }
                case 'Z', 'z' -> {
                    path.closePath();
                    x = subX;
                    y = subY;
                    hasLastCubic = false;
                    hasLastQuad = false;
                    if (tokens.hasNext() && !tokens.peekCommand()) {
                        tokens.skip();
                    }
                }
                default -> {
                    if (tokens.hasNext()) {
                        tokens.skip();
                    }
                }
            }
            if (tokens.position() == start) {
                tokens.skip(); // Drop leftover numbers that don't fill a group, so the loop can't spin in place.
            }
        }
        return path;
    }

    /** Cursor over the path tokens. */
    private static final class TokenStream {
        private final List<String> tokens;
        private int index;

        private TokenStream(String data) {
            this.tokens = tokenize(data);
        }

        boolean hasNext() {
            return index < tokens.size();
        }

        int position() {
            return index;
        }

        boolean peekCommand() {
            return hasNext() && isCommand(tokens.get(index));
        }

        char nextCommand() {
            return tokens.get(index++).charAt(0);
        }

        boolean hasNumber() {
            return hasNext() && !peekCommand();
        }

        boolean hasNumberPair() {
            return remainingNumbersBeforeCommand() >= 2;
        }

        double nextNumber() {
            return Double.parseDouble(tokens.get(index++));
        }

        int remainingNumbersBeforeCommand() {
            int count = 0;
            for (int i = index; i < tokens.size(); i++) {
                if (isCommand(tokens.get(i))) {
                    break;
                }
                count++;
            }
            return count;
        }

        void skip() {
            index++;
        }

        /**
         * Reads one group of arc parameters, or returns null if too few are left; the two flags may be written
         * without separators (as in {@code 0150}), so each is split off as a single character.
         */
        double[] nextArc() {
            double[] values = new double[7];
            for (int i = 0; i < values.length; i++) {
                if (!hasNumber()) {
                    return null;
                }
                values[i] = i == 3 || i == 4 ? nextFlag() : nextNumber();
            }
            return values;
        }

        private double nextFlag() {
            String token = tokens.get(index);
            char first = token.charAt(0);
            if (token.length() > 1 && (first == '0' || first == '1')) {
                tokens.set(index, token.substring(1));
                return first - '0';
            }
            index++;
            return Double.parseDouble(token);
        }

        private static List<String> tokenize(String data) {
            List<String> tokens = new ArrayList<>();
            int i = 0;
            while (i < data.length()) {
                char ch = data.charAt(i);
                if (Character.isWhitespace(ch) || ch == ',') {
                    i++;
                    continue;
                }
                if (isCommand(ch)) {
                    tokens.add(String.valueOf(ch));
                    i++;
                    continue;
                }

                int start = i;
                if (ch == '+' || ch == '-') {
                    i++;
                }
                boolean hasDot = false;
                boolean hasExp = false;
                while (i < data.length()) {
                    ch = data.charAt(i);
                    if (Character.isDigit(ch)) {
                        i++;
                    } else if (ch == '.' && !hasDot) {
                        hasDot = true;
                        i++;
                    } else if ((ch == 'e' || ch == 'E') && !hasExp) {
                        hasExp = true;
                        i++;
                        if (i < data.length() && (data.charAt(i) == '+' || data.charAt(i) == '-')) {
                            i++;
                        }
                    } else {
                        break;
                    }
                }
                if (start != i) {
                    tokens.add(data.substring(start, i));
                } else {
                    i++;
                }
            }
            return tokens;
        }

        private static boolean isCommand(String token) {
            return token.length() == 1 && isCommand(token.charAt(0));
        }

        private static boolean isCommand(char ch) {
            return switch (ch) {
                case 'M', 'm', 'L', 'l', 'H', 'h', 'V', 'v', 'C', 'c', 'S', 's',
                     'Q', 'q', 'T', 't', 'A', 'a', 'Z', 'z' -> true;
                default -> false;
            };
        }
    }
}
