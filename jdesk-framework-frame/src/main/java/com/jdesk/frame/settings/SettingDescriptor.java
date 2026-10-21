package com.jdesk.frame.settings;

import com.jdesk.core.i18n.I18n;
import com.jdesk.core.runtime.RuntimeParamMutability;
import com.jdesk.core.runtime.RuntimeParamDescriptor;
import com.jdesk.core.runtime.RuntimeParamType;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Metadata of a user setting: ID, display name, group, value type, default, choices, numeric range, search keywords
 * and an optional runtime parameter binding, from which the settings manager parses, validates and builds the UI.
 * Display name, description and group name may be suppliers, read on every call.
 */
public final class SettingDescriptor {

    private final String id;
    private final Supplier<String> displayName;
    private final Supplier<String> description;
    private final String groupId;
    private final Supplier<String> groupDisplayName;
    private final SettingType type;
    private final Object defaultValue;
    private final List<SettingChoice> choices;
    private final Double min;
    private final Double max;
    private final Double step;
    private final Set<String> keywords;
    private final RuntimeSettingBinding runtimeBinding;

    private SettingDescriptor(Builder builder) {
        this.id = requireText(builder.id, "id");
        this.displayName = requireText(builder.displayName, "displayName");
        this.description = builder.description == null ? () -> "" : builder.description;
        this.groupId = requireText(builder.groupId, "groupId");
        this.groupDisplayName = requireText(builder.groupDisplayName, "groupDisplayName");
        this.type = Objects.requireNonNull(builder.type, "type");
        this.choices = List.copyOf(builder.choices);
        this.min = builder.min;
        this.max = builder.max;
        this.step = builder.step;
        this.keywords = Set.copyOf(builder.keywords);
        this.runtimeBinding = builder.runtimeBinding;
        this.defaultValue = normalizeValue(builder.defaultValue);
        validateDefault();
    }

    public static Builder builder(String id, SettingType type) {
        return new Builder(id, type);
    }

    public static Builder booleanSetting(String id, String displayName, boolean defaultValue) {
        return builder(id, SettingType.BOOLEAN).displayName(displayName).defaultValue(defaultValue);
    }

    public static Builder integerSetting(String id, String displayName, int defaultValue) {
        return builder(id, SettingType.INTEGER).displayName(displayName).defaultValue(defaultValue);
    }

    public static Builder stringSetting(String id, String displayName, String defaultValue) {
        return builder(id, SettingType.STRING).displayName(displayName).defaultValue(defaultValue);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName.get();
    }

    /** Returns the description, or an empty string rather than {@code null}. */
    public String getDescription() {
        String text = description.get();
        return text == null ? "" : text;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getGroupDisplayName() {
        return groupDisplayName.get();
    }

    public SettingType getType() {
        return type;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public List<SettingChoice> getChoices() {
        return choices;
    }

    public Double getMin() {
        return min;
    }

    public Double getMax() {
        return max;
    }

    public Double getStep() {
        return step;
    }

    public Set<String> getKeywords() {
        return keywords;
    }

    public RuntimeSettingBinding getRuntimeBinding() {
        return runtimeBinding;
    }

    /**
     * Parses a saved string, checking its type, choices and range; {@code null} means nothing is saved and yields
     * the default.
     */
    public Object parse(String rawValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        return normalizeValue(type.parse(rawValue));
    }

    /**
     * Normalizes a value to this setting's type and checks its choices and range.
     *
     * @throws IllegalArgumentException if the value isn't allowed
     */
    public Object normalizeValue(Object value) {
        Object normalized = type.normalize(value);
        validateChoices(normalized);
        validateRange(normalized);
        return normalized;
    }

    /** Normalizes a value and formats it as the saved string. */
    public String format(Object value) {
        return type.format(normalizeValue(value));
    }

    /** Maps the setting type to the core runtime parameter type. */
    public RuntimeParamType toRuntimeParamType() {
        return toRuntimeParamType(type);
    }

    private static RuntimeParamType toRuntimeParamType(SettingType type) {
        return switch (type) {
            case BOOLEAN -> RuntimeParamType.BOOLEAN;
            case INTEGER -> RuntimeParamType.INTEGER;
            case DOUBLE -> RuntimeParamType.DOUBLE;
            case STRING, CHOICE -> RuntimeParamType.STRING;
        };
    }

    private void validateDefault() {
        if (type == SettingType.CHOICE && choices.isEmpty()) {
            throw new IllegalStateException("Choice setting requires choices: " + id);
        }
        if (runtimeBinding != null && runtimeBinding.getDescriptor().getType() != toRuntimeParamType()) {
            throw new IllegalStateException("Runtime binding type mismatch: " + id);
        }
        validateChoices(defaultValue);
        validateRange(defaultValue);
    }

    private void validateChoices(Object value) {
        if (type != SettingType.CHOICE || choices.isEmpty()) {
            return;
        }
        String text = value.toString();
        for (SettingChoice choice : choices) {
            if (choice.getValue().equals(text)) {
                return;
            }
        }
        throw new IllegalArgumentException("Unsupported choice '" + text + "' for setting " + id);
    }

    private void validateRange(Object value) {
        if (min == null && max == null) {
            return;
        }
        if (!(value instanceof Number number)) {
            return;
        }
        double numeric = number.doubleValue();
        if (min != null && numeric < min) {
            throw new IllegalArgumentException("Setting " + id + " is below minimum " + min);
        }
        if (max != null && numeric > max) {
            throw new IllegalArgumentException("Setting " + id + " is above maximum " + max);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    /** Checks the supplier's current value once and returns the supplier itself. */
    private static Supplier<String> requireText(Supplier<String> value, String name) {
        requireText(value == null ? null : value.get(), name);
        return value;
    }

    /** Builder of setting descriptors; the setters chain. */
    public static final class Builder {

        private final String id;
        private final SettingType type;
        private Supplier<String> displayName;
        private Supplier<String> description;
        private String groupId = SettingsManager.GENERAL_GROUP_ID;
        private Supplier<String> groupDisplayName = () -> I18n.message("frame.settings.group.general");
        private Object defaultValue;
        private List<SettingChoice> choices = List.of();
        private Double min;
        private Double max;
        private Double step;
        private final Set<String> keywords = new LinkedHashSet<>();
        private RuntimeSettingBinding runtimeBinding;

        private Builder(String id, SettingType type) {
            this.id = id;
            this.type = type;
        }

        public Builder displayName(String displayName) {
            return displayName(() -> displayName);
        }

        public Builder displayName(Supplier<String> displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            return description(() -> description);
        }

        public Builder description(Supplier<String> description) {
            this.description = description;
            return this;
        }

        public Builder group(String groupId, String groupDisplayName) {
            return group(groupId, () -> groupDisplayName);
        }

        public Builder group(String groupId, Supplier<String> groupDisplayName) {
            this.groupId = groupId;
            this.groupDisplayName = groupDisplayName;
            return this;
        }

        public Builder defaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder choices(SettingChoice... choices) {
            this.choices = List.copyOf(Arrays.asList(choices));
            return this;
        }

        public Builder range(Number min, Number max, Number step) {
            this.min = min == null ? null : min.doubleValue();
            this.max = max == null ? null : max.doubleValue();
            this.step = step == null ? null : step.doubleValue();
            return this;
        }

        public Builder keywords(String... keywords) {
            this.keywords.addAll(Arrays.asList(keywords));
            return this;
        }

        public Builder runtimeBinding(RuntimeParamDescriptor descriptor) {
            this.runtimeBinding = RuntimeSettingBinding.of(descriptor);
            return this;
        }

        /**
         * @deprecated Declare a {@link RuntimeParamDescriptor} in the module's runtime params package
         * and call {@link #runtimeBinding(RuntimeParamDescriptor)}.
         */
        @Deprecated
        public Builder runtimeBinding(String runtimeKey, RuntimeParamMutability mutability) {
            RuntimeParamDescriptor descriptor = RuntimeParamDescriptor.builder(runtimeKey, toRuntimeParamType(type))
                    .description(description == null ? null : description.get())
                    .mutability(mutability)
                    .build();
            this.runtimeBinding = RuntimeSettingBinding.of(descriptor);
            return this;
        }

        public SettingDescriptor build() {
            String name = displayName == null ? null : displayName.get();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("Setting display name is required: " + id);
            }
            if (defaultValue == null) {
                throw new IllegalStateException("Setting default value is required: " + id);
            }
            return new SettingDescriptor(this);
        }
    }
}
