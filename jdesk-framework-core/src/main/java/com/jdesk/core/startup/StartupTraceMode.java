package com.jdesk.core.startup;

import java.util.Locale;

/**
 * Startup trace operating mode loaded from startup configuration.
 */
public enum StartupTraceMode {
    REPORT("report"),
    TIME("time"),
    OFF("false");

    private final String configValue;

    StartupTraceMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public boolean recordsSpans() {
        return this == REPORT;
    }

    public boolean outputsReport() {
        return this == REPORT;
    }

    public boolean outputsTotalTime() {
        return this == TIME;
    }

    public boolean isOff() {
        return this == OFF;
    }

    public static StartupTraceMode parse(String value) {
        if (value == null || value.isBlank()) {
            return TIME;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "report" -> REPORT;
            case "time" -> TIME;
            case "false" -> OFF;
            default -> throw new IllegalArgumentException("Unknown startup trace mode: " + value);
        };
    }
}
