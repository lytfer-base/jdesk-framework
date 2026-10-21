package com.jdesk.core.runtime;

import java.util.Objects;

/**
 * Immutable snapshot of a runtime parameter's current value with its descriptor and source, so listeners can tell a
 * default from a system property, a user setting or a programmatic change.
 */
public final class RuntimeParamValue {

    private final RuntimeParamDescriptor descriptor;
    private final Object value;
    private final RuntimeParamSource source;

    RuntimeParamValue(RuntimeParamDescriptor descriptor,
                      Object value,
                      RuntimeParamSource source) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.value = descriptor.getType().normalize(value);
        this.source = Objects.requireNonNull(source, "source");
    }

    public RuntimeParamDescriptor getDescriptor() {
        return descriptor;
    }

    public String getKey() {
        return descriptor.getKey();
    }

    public RuntimeParamType getType() {
        return descriptor.getType();
    }

    public Object getValue() {
        return value;
    }

    public RuntimeParamSource getSource() {
        return source;
    }

    public String asString() {
        return descriptor.getType().format(value);
    }

    public boolean asBoolean() {
        return (Boolean) RuntimeParamType.BOOLEAN.normalize(value);
    }

    public int asInteger() {
        return (Integer) RuntimeParamType.INTEGER.normalize(value);
    }

    public long asLong() {
        return (Long) RuntimeParamType.LONG.normalize(value);
    }

    public double asDouble() {
        return (Double) RuntimeParamType.DOUBLE.normalize(value);
    }
}
