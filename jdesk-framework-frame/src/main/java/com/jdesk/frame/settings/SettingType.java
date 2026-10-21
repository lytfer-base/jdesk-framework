package com.jdesk.frame.settings;

import java.util.Locale;

/** Value types of user settings; each normalizes input to its internal value and parses and formats strings. */
public enum SettingType {
    BOOLEAN,
    INTEGER,
    DOUBLE,
    STRING,
    CHOICE;

    public Object normalize(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Setting value cannot be null");
        }
        return switch (this) {
            case BOOLEAN -> normalizeBoolean(value);
            case INTEGER -> normalizeInteger(value);
            case DOUBLE -> normalizeDouble(value);
            case STRING, CHOICE -> value.toString();
        };
    }

    public Object parse(String value) {
        return normalize(value);
    }

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
        throw new IllegalArgumentException("Expected boolean setting value but got: " + value);
    }

    private static Integer normalizeInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString().trim());
    }

    private static Double normalizeDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString().trim());
    }
}
