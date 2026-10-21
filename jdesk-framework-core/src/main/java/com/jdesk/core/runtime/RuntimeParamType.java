package com.jdesk.core.runtime;

import java.util.Locale;

/**
 * Value types of runtime parameters; each one normalizes input to a stable Java type that the registry stores and
 * compares.
 */
public enum RuntimeParamType {
    BOOLEAN,
    INTEGER,
    LONG,
    DOUBLE,
    STRING;

    /** Normalizes {@code value} to the Java type of this parameter type. */
    public Object normalize(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Runtime parameter value cannot be null");
        }
        return switch (this) {
            case BOOLEAN -> normalizeBoolean(value);
            case INTEGER -> normalizeInteger(value);
            case LONG -> normalizeLong(value);
            case DOUBLE -> normalizeDouble(value);
            case STRING -> value.toString();
        };
    }

    /** Normalizes {@code value} and formats it as a string. */
    public String format(Object value) {
        return normalize(value).toString();
    }

    private static Boolean normalizeBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text)) {
            return true;
        }
        if ("false".equals(text)) {
            return false;
        }
        throw new IllegalArgumentException("Expected boolean value but got: " + value);
    }

    private static Integer normalizeInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString().trim());
    }

    private static Long normalizeLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString().trim());
    }

    private static Double normalizeDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString().trim());
    }
}
