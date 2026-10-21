package com.jdesk.core.runtime;

import java.util.Objects;

/**
 * Metadata of a runtime parameter: key, type and mutability, which the registry uses to normalize values and to check
 * startup configuration and runtime changes.
 */
public final class RuntimeParamDescriptor {

    private final String key;
    private final RuntimeParamType type;
    private final String description;
    private final RuntimeParamMutability mutability;

    private RuntimeParamDescriptor(Builder builder) {
        this.key = requireText(builder.key, "key");
        this.type = Objects.requireNonNull(builder.type, "type");
        this.description = builder.description == null ? "" : builder.description;
        this.mutability = Objects.requireNonNull(builder.mutability, "mutability");
    }

    /** Returns a builder for a parameter with {@code key} and {@code type}. */
    public static Builder builder(String key, RuntimeParamType type) {
        return new Builder(key, type);
    }

    public String getKey() {
        return key;
    }

    public RuntimeParamType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public RuntimeParamMutability getMutability() {
        return mutability;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    /** Builds descriptors; mutability defaults to {@link RuntimeParamMutability#LOCKED_AFTER_BOOT}. */
    public static final class Builder {

        private final String key;
        private final RuntimeParamType type;
        private String description;
        private RuntimeParamMutability mutability = RuntimeParamMutability.LOCKED_AFTER_BOOT;

        private Builder(String key, RuntimeParamType type) {
            this.key = key;
            this.type = type;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder mutability(RuntimeParamMutability mutability) {
            this.mutability = mutability;
            return this;
        }

        public RuntimeParamDescriptor build() {
            return new RuntimeParamDescriptor(this);
        }
    }
}
