package com.jdesk.frame.settings;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * One option of a {@link SettingType#CHOICE} setting: {@code value} is the stable value that is saved and used by
 * runtime parameters, and {@code label} is the text shown, read from its supplier on every call.
 */
public final class SettingChoice {

    private final String value;
    private final Supplier<String> label;

    public SettingChoice(String value, String label) {
        this.value = requireText(value, "value");
        String text = requireText(label, "label");
        this.label = () -> text;
    }

    private SettingChoice(String value, Supplier<String> label) {
        this.value = requireText(value, "value");
        this.label = Objects.requireNonNull(label, "label");
    }

    public static SettingChoice of(String value, String label) {
        return new SettingChoice(value, label);
    }

    public static SettingChoice of(String value, Supplier<String> label) {
        return new SettingChoice(value, label);
    }

    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label.get();
    }

    @Override
    public String toString() {
        return getLabel();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SettingChoice that)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
