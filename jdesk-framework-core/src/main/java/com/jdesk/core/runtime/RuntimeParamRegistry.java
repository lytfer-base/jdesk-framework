package com.jdesk.core.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Global registry of application runtime parameters, the values that runtime logic must read quickly; settings pages
 * and UI metadata belong to the frame layer, which can bridge them in. Reads, writes and listener registration may run
 * concurrently, while {@link #completeBoot()} and {@link #reset()} belong to the startup and shutdown sequence. The
 * state is per JVM behind static methods; reset it on shutdown or between tests.
 */
public final class RuntimeParamRegistry {

    private static final RuntimeParamRegistry INSTANCE = new RuntimeParamRegistry();

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RuntimeParamChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean bootCompleted;

    private RuntimeParamRegistry() {
    }

    /** Clears all runtime parameter state. */
    public static void reset() {
        INSTANCE.entries.clear();
        INSTANCE.listeners.clear();
        INSTANCE.bootCompleted = false;
    }

    /** Returns whether a parameter with {@code key} is registered. */
    public static boolean contains(String key) {
        return INSTANCE.entries.containsKey(key);
    }

    public static boolean contains(RuntimeParamDescriptor descriptor) {
        return contains(Objects.requireNonNull(descriptor, "descriptor").getKey());
    }

    /** Returns the descriptor of a parameter. */
    public static RuntimeParamDescriptor getDescriptor(String key) {
        return entry(key).descriptor;
    }

    /** Returns the current value snapshot of a parameter. */
    public static RuntimeParamValue get(String key) {
        RuntimeParamValue value = entry(key).value;
        if (value == null) {
            throw new IllegalStateException("Runtime parameter has no value: " + key);
        }
        return value;
    }

    public static RuntimeParamValue get(RuntimeParamDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        Entry entry = entry(descriptor.getKey());
        synchronized (entry) {
            if (!entry.generic) {
                ensureCompatible(entry.descriptor, descriptor);
            }
            RuntimeParamValue value = entry.value;
            if (value == null) {
                throw new IllegalStateException("Runtime parameter has no value: " + descriptor.getKey());
            }
            return new RuntimeParamValue(descriptor, value.getValue(), value.getSource());
        }
    }

    public static Object getValue(String key) {
        return get(key).getValue();
    }

    public static Object getValue(RuntimeParamDescriptor descriptor) {
        return get(descriptor).getValue();
    }

    public static boolean getBoolean(String key) {
        return get(key).asBoolean();
    }

    public static boolean getBoolean(RuntimeParamDescriptor descriptor) {
        return get(descriptor).asBoolean();
    }

    public static boolean getBoolean(RuntimeParamDescriptor descriptor, boolean defaultValue) {
        RuntimeParamValue value = getIfPresent(descriptor);
        return value == null ? defaultValue : value.asBoolean();
    }

    public static int getInt(String key) {
        return get(key).asInteger();
    }

    public static int getInt(RuntimeParamDescriptor descriptor) {
        return get(descriptor).asInteger();
    }

    public static int getInt(RuntimeParamDescriptor descriptor, int defaultValue) {
        RuntimeParamValue value = getIfPresent(descriptor);
        return value == null ? defaultValue : value.asInteger();
    }

    public static long getLong(String key) {
        return get(key).asLong();
    }

    public static long getLong(RuntimeParamDescriptor descriptor) {
        return get(descriptor).asLong();
    }

    public static long getLong(RuntimeParamDescriptor descriptor, long defaultValue) {
        RuntimeParamValue value = getIfPresent(descriptor);
        return value == null ? defaultValue : value.asLong();
    }

    public static double getDouble(String key) {
        return get(key).asDouble();
    }

    public static double getDouble(RuntimeParamDescriptor descriptor) {
        return get(descriptor).asDouble();
    }

    public static double getDouble(RuntimeParamDescriptor descriptor, double defaultValue) {
        RuntimeParamValue value = getIfPresent(descriptor);
        return value == null ? defaultValue : value.asDouble();
    }

    public static String getString(String key) {
        return get(key).asString();
    }

    public static String getString(RuntimeParamDescriptor descriptor) {
        return get(descriptor).asString();
    }

    public static String getString(RuntimeParamDescriptor descriptor, String defaultValue) {
        RuntimeParamValue value = getIfPresent(descriptor);
        return value == null ? defaultValue : value.asString();
    }

    /** Sets a value during startup; fails once boot is complete, so the mutability policy can't be bypassed. */
    public static void setStartupValue(RuntimeParamDescriptor descriptor, Object value, RuntimeParamSource source) {
        setValueInternal(Objects.requireNonNull(descriptor, "descriptor"), value, source, true);
    }

    public static void setStartupValue(String key, Object value, RuntimeParamSource source) {
        setValueInternal(key, value, source, true);
    }

    /** Sets a value; after boot the change is checked against the descriptor's mutability. */
    public static void setValue(RuntimeParamDescriptor descriptor, Object value, RuntimeParamSource source) {
        setValueInternal(descriptor, value, source, false);
    }

    public static void setValue(String key, Object value, RuntimeParamSource source) {
        setValueInternal(key, value, source, false);
    }

    /**
     * Writes a value that may arrive before or after boot: like a startup value before, like
     * {@link #setValue(RuntimeParamDescriptor, Object, RuntimeParamSource)} after. Bridges such as settings use it
     * instead of checking the boot state themselves.
     */
    public static void setStartupOrRuntimeValue(RuntimeParamDescriptor descriptor,
                                                Object value,
                                                RuntimeParamSource source) {
        setValueInternal(descriptor, value, source, false);
    }

    /** Ends the startup phase; {@link RuntimeParamMutability#LOCKED_AFTER_BOOT} parameters then reject changes. */
    public static void completeBoot() {
        INSTANCE.bootCompleted = true;
    }

    public static boolean isBootCompleted() {
        return INSTANCE.bootCompleted;
    }

    /** Adds a change listener and returns a handle whose {@code close()} removes it. */
    public static AutoCloseable addChangeListener(RuntimeParamChangeListener listener) {
        INSTANCE.listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> INSTANCE.listeners.remove(listener);
    }

    /** Returns a snapshot of all registered keys. */
    public static Set<String> getKeys() {
        return Set.copyOf(INSTANCE.entries.keySet());
    }

    /** Returns a snapshot of all descriptors, sorted by key. */
    public static List<RuntimeParamDescriptor> getDescriptors() {
        return INSTANCE.entries.values().stream()
                .map(entry -> entry.descriptor)
                .sorted((left, right) -> left.getKey().compareTo(right.getKey()))
                .toList();
    }

    private static void setValueInternal(RuntimeParamDescriptor descriptor,
                                         Object rawValue,
                                         RuntimeParamSource source,
                                         boolean startupOnly) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(descriptor, "descriptor");
        RuntimeParamValue[] changedValues = new RuntimeParamValue[2];
        INSTANCE.entries.compute(descriptor.getKey(), (key, current) -> {
            Entry entry = current == null ? new Entry(descriptor, false) : current;
            synchronized (entry) {
                if (startupOnly && INSTANCE.bootCompleted) {
                    throw new IllegalStateException(
                            "Cannot set startup value after boot is completed: " + descriptor.getKey());
                }
                RuntimeParamDescriptor effectiveDescriptor = descriptor;
                if (current != null && !entry.generic) {
                    ensureCompatible(entry.descriptor, descriptor);
                    effectiveDescriptor = entry.descriptor;
                }
                Object normalizedValue = effectiveDescriptor.getType().normalize(rawValue);
                ensureCanChange(effectiveDescriptor);
                RuntimeParamValue oldValue = entry.value;
                if (oldValue != null && Objects.equals(oldValue.getValue(), normalizedValue)) {
                    if (entry.generic) {
                        entry.descriptor = effectiveDescriptor;
                        entry.generic = false;
                        entry.value = new RuntimeParamValue(
                                effectiveDescriptor, oldValue.getValue(), oldValue.getSource());
                    }
                    return entry;
                }
                RuntimeParamValue newValue = new RuntimeParamValue(effectiveDescriptor, normalizedValue, source);
                entry.descriptor = effectiveDescriptor;
                entry.generic = false;
                entry.value = newValue;
                changedValues[0] = oldValue;
                changedValues[1] = newValue;
                return entry;
            }
        });
        if (changedValues[0] != null) {
            notifyChanged(changedValues[0], changedValues[1]);
        }
    }

    private static void setValueInternal(String key,
                                         Object rawValue,
                                         RuntimeParamSource source,
                                         boolean startupOnly) {
        Objects.requireNonNull(source, "source");
        String normalizedKey = requireKey(key);
        RuntimeParamValue[] changedValues = new RuntimeParamValue[2];
        INSTANCE.entries.compute(normalizedKey, (entryKey, current) -> {
            Entry entry = current == null
                    ? new Entry(genericStringDescriptor(entryKey), true)
                    : current;
            synchronized (entry) {
                if (startupOnly && INSTANCE.bootCompleted) {
                    throw new IllegalStateException(
                            "Cannot set startup value after boot is completed: " + normalizedKey);
                }
                Object normalizedValue = entry.descriptor.getType().normalize(rawValue);
                ensureCanChange(entry.descriptor);
                RuntimeParamValue oldValue = entry.value;
                if (oldValue != null && Objects.equals(oldValue.getValue(), normalizedValue)) {
                    return entry;
                }
                RuntimeParamValue newValue = new RuntimeParamValue(entry.descriptor, normalizedValue, source);
                entry.value = newValue;
                changedValues[0] = oldValue;
                changedValues[1] = newValue;
                return entry;
            }
        });
        if (changedValues[0] != null) {
            notifyChanged(changedValues[0], changedValues[1]);
        }
    }

    private static void ensureCanChange(RuntimeParamDescriptor descriptor) {
        if (!INSTANCE.bootCompleted) {
            return;
        }

        switch (descriptor.getMutability()) {
            case MUTABLE -> {
            }
            case LOCKED_AFTER_BOOT -> throw new IllegalStateException(
                    "Runtime parameter is locked after boot: " + descriptor.getKey());
        }
    }

    private static RuntimeParamDescriptor genericStringDescriptor(String key) {
        return RuntimeParamDescriptor.builder(requireKey(key), RuntimeParamType.STRING)
                .description("Application startup property.")
                .build();
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Runtime parameter key cannot be blank");
        }
        return key;
    }

    private static Entry entry(String key) {
        Entry entry = INSTANCE.entries.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown runtime parameter: " + key);
        }
        return entry;
    }

    private static RuntimeParamValue getIfPresent(RuntimeParamDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        Entry entry = INSTANCE.entries.get(descriptor.getKey());
        if (entry == null) {
            return null;
        }
        synchronized (entry) {
            if (!entry.generic) {
                ensureCompatible(entry.descriptor, descriptor);
            }
            RuntimeParamValue value = entry.value;
            if (value == null) {
                return null;
            }
            return new RuntimeParamValue(descriptor, value.getValue(), value.getSource());
        }
    }

    private static void ensureCompatible(RuntimeParamDescriptor existing,
                                         RuntimeParamDescriptor incoming) {
        if (existing.getType() != incoming.getType()) {
            throw new IllegalStateException("Runtime parameter type mismatch: " + existing.getKey());
        }
        if (existing.getMutability() != incoming.getMutability()) {
            throw new IllegalStateException("Runtime parameter mutability mismatch: " + existing.getKey());
        }
    }

    private static void notifyChanged(RuntimeParamValue oldValue, RuntimeParamValue newValue) {
        for (RuntimeParamChangeListener listener : INSTANCE.listeners) {
            listener.parameterChanged(oldValue, newValue);
        }
    }

    private static final class Entry {

        private volatile RuntimeParamDescriptor descriptor;
        private volatile boolean generic;
        private volatile RuntimeParamValue value;

        private Entry(RuntimeParamDescriptor descriptor, boolean generic) {
            this.descriptor = descriptor;
            this.generic = generic;
        }
    }
}
