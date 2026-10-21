package com.jdesk.core.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeParamRegistryTest {

    @AfterEach
    void resetRegistry() {
        RuntimeParamRegistry.reset();
    }

    @Test
    void parameterIsCreatedWhenStartupValueIsWritten() {
        RuntimeParamDescriptor descriptor = RuntimeParamDescriptor.builder(
                        "toolbar.ui.default.height", RuntimeParamType.INTEGER)
                .description("Default toolbar height")
                .build();

        assertTrue(RuntimeParamRegistry.getKeys().isEmpty());

        RuntimeParamRegistry.setStartupValue(descriptor, 36, RuntimeParamSource.STARTUP_CONFIG);

        assertEquals(36, RuntimeParamRegistry.getInt("toolbar.ui.default.height"));
        assertEquals(RuntimeParamSource.STARTUP_CONFIG,
                RuntimeParamRegistry.get("toolbar.ui.default.height").getSource());
    }

    @Test
    void unknownStartupKeyIsStoredAsStringAndCanBeReadThroughDescriptor() {
        RuntimeParamDescriptor descriptor = RuntimeParamDescriptor.builder("feature.fast.path", RuntimeParamType.BOOLEAN)
                .build();

        RuntimeParamRegistry.setStartupValue("feature.fast.path", "true", RuntimeParamSource.STARTUP_CONFIG);

        assertEquals("true", RuntimeParamRegistry.getString("feature.fast.path"));
        assertTrue(RuntimeParamRegistry.getBoolean(descriptor));
        assertEquals(RuntimeParamSource.STARTUP_CONFIG, RuntimeParamRegistry.get(descriptor).getSource());
    }

    @Test
    void genericStartupEntryCanBeUpgradedToMutableDescriptorAfterBoot() {
        RuntimeParamDescriptor descriptor = RuntimeParamDescriptor.builder("feature.fast.path", RuntimeParamType.BOOLEAN)
                .mutability(RuntimeParamMutability.MUTABLE)
                .build();

        RuntimeParamRegistry.setStartupValue("feature.fast.path", "true", RuntimeParamSource.STARTUP_CONFIG);
        RuntimeParamRegistry.completeBoot();
        RuntimeParamRegistry.setValue(descriptor, false, RuntimeParamSource.USER_SETTING);

        assertFalse(RuntimeParamRegistry.getBoolean(descriptor));
        assertEquals(RuntimeParamSource.USER_SETTING, RuntimeParamRegistry.get(descriptor).getSource());
    }

    @Test
    void descriptorMismatchIsRejected() {
        RuntimeParamDescriptor descriptor = RuntimeParamDescriptor.builder(
                        "toolbar.ui.default.height", RuntimeParamType.INTEGER)
                .description("Default toolbar height")
                .build();
        RuntimeParamDescriptor incompatibleDescriptor = RuntimeParamDescriptor.builder(
                        "toolbar.ui.default.height", RuntimeParamType.DOUBLE)
                .description("Default toolbar height")
                .build();

        RuntimeParamRegistry.setStartupValue(descriptor, 36, RuntimeParamSource.STARTUP_CONFIG);

        assertThrows(IllegalStateException.class,
                () -> RuntimeParamRegistry.setStartupValue(incompatibleDescriptor, 36, RuntimeParamSource.STARTUP_CONFIG));
    }

    @Test
    void lockedParameterAcceptsStartupValueButRejectsRuntimeMutation() {
        RuntimeParamDescriptor descriptor = RuntimeParamDescriptor.builder(
                        "feature.fast.path", RuntimeParamType.BOOLEAN)
                .build();

        RuntimeParamRegistry.setStartupValue(descriptor, true, RuntimeParamSource.STARTUP_CONFIG);
        RuntimeParamRegistry.completeBoot();

        assertTrue(RuntimeParamRegistry.getBoolean("feature.fast.path"));
        assertThrows(IllegalStateException.class,
                () -> RuntimeParamRegistry.setValue("feature.fast.path", false, RuntimeParamSource.PROGRAMMATIC));
    }

    @Test
    void startupValueIsRejectedAfterBoot() {
        RuntimeParamDescriptor descriptor = RuntimeParamDescriptor.builder(
                        "feature.fast.path", RuntimeParamType.BOOLEAN)
                .mutability(RuntimeParamMutability.MUTABLE)
                .build();

        RuntimeParamRegistry.completeBoot();

        assertThrows(IllegalStateException.class,
                () -> RuntimeParamRegistry.setStartupValue(descriptor, true, RuntimeParamSource.STARTUP_CONFIG));
        assertThrows(IllegalStateException.class,
                () -> RuntimeParamRegistry.setStartupValue(
                        "feature.fast.path", "true", RuntimeParamSource.STARTUP_CONFIG));
    }

    @Test
    void defaultValueReadersReturnDefaultForUnknownParameter() {
        RuntimeParamDescriptor booleanDescriptor = RuntimeParamDescriptor.builder(
                        "feature.fast.path", RuntimeParamType.BOOLEAN)
                .build();
        RuntimeParamDescriptor integerDescriptor = RuntimeParamDescriptor.builder(
                        "toolbar.ui.default.height", RuntimeParamType.INTEGER)
                .build();
        RuntimeParamDescriptor longDescriptor = RuntimeParamDescriptor.builder(
                        "cache.max.bytes", RuntimeParamType.LONG)
                .build();
        RuntimeParamDescriptor doubleDescriptor = RuntimeParamDescriptor.builder(
                        "ui.scale", RuntimeParamType.DOUBLE)
                .build();
        RuntimeParamDescriptor stringDescriptor = RuntimeParamDescriptor.builder(
                        "startup.trace.mode", RuntimeParamType.STRING)
                .build();

        assertTrue(RuntimeParamRegistry.getBoolean(booleanDescriptor, true));
        assertEquals(36, RuntimeParamRegistry.getInt(integerDescriptor, 36));
        assertEquals(1024L, RuntimeParamRegistry.getLong(longDescriptor, 1024L));
        assertEquals(1.25, RuntimeParamRegistry.getDouble(doubleDescriptor, 1.25), 0.001);
        assertEquals("report", RuntimeParamRegistry.getString(stringDescriptor, "report"));
    }

    @Test
    void startupOrRuntimeValueUsesRuntimeRulesAfterBoot() {
        RuntimeParamDescriptor lockedDescriptor = RuntimeParamDescriptor.builder(
                        "feature.locked", RuntimeParamType.BOOLEAN)
                .build();
        RuntimeParamDescriptor mutableDescriptor = RuntimeParamDescriptor.builder(
                        "feature.mutable", RuntimeParamType.BOOLEAN)
                .mutability(RuntimeParamMutability.MUTABLE)
                .build();

        RuntimeParamRegistry.setStartupOrRuntimeValue(lockedDescriptor, true, RuntimeParamSource.STARTUP_CONFIG);
        RuntimeParamRegistry.setStartupOrRuntimeValue(mutableDescriptor, true, RuntimeParamSource.STARTUP_CONFIG);
        RuntimeParamRegistry.completeBoot();

        assertThrows(IllegalStateException.class,
                () -> RuntimeParamRegistry.setStartupOrRuntimeValue(
                        lockedDescriptor, false, RuntimeParamSource.USER_SETTING));

        RuntimeParamRegistry.setStartupOrRuntimeValue(mutableDescriptor, false, RuntimeParamSource.USER_SETTING);

        assertTrue(RuntimeParamRegistry.getBoolean(lockedDescriptor));
        assertFalse(RuntimeParamRegistry.getBoolean(mutableDescriptor));
    }

    @Test
    void mutableParameterCanChangeAfterBootAndNotifiesListeners() throws Exception {
        RuntimeParamDescriptor descriptor = RuntimeParamDescriptor.builder("ui.scale", RuntimeParamType.DOUBLE)
                .mutability(RuntimeParamMutability.MUTABLE)
                .build();
        RuntimeParamRegistry.setStartupValue(descriptor, 1.0, RuntimeParamSource.STARTUP_CONFIG);

        AtomicReference<RuntimeParamValue> changedValue = new AtomicReference<>();
        AutoCloseable subscription = RuntimeParamRegistry.addChangeListener((oldValue, newValue) -> changedValue.set(newValue));

        RuntimeParamRegistry.completeBoot();
        RuntimeParamRegistry.setValue("ui.scale", 1.25, RuntimeParamSource.USER_SETTING);

        assertEquals(1.25, RuntimeParamRegistry.getDouble("ui.scale"), 0.001);
        assertEquals(RuntimeParamSource.USER_SETTING, changedValue.get().getSource());

        subscription.close();
        RuntimeParamRegistry.setValue("ui.scale", 1.5, RuntimeParamSource.USER_SETTING);
        assertEquals(1.25, changedValue.get().asDouble(), 0.001);
    }

    @Test
    void resetClearsEntriesListenersAndBootState() {
        RuntimeParamDescriptor descriptor = RuntimeParamDescriptor.builder("ui.scale", RuntimeParamType.DOUBLE)
                .mutability(RuntimeParamMutability.MUTABLE)
                .build();
        AtomicReference<RuntimeParamValue> changedValue = new AtomicReference<>();

        RuntimeParamRegistry.addChangeListener((oldValue, newValue) -> changedValue.set(newValue));
        RuntimeParamRegistry.setStartupValue(descriptor, 1.0, RuntimeParamSource.STARTUP_CONFIG);
        RuntimeParamRegistry.completeBoot();

        RuntimeParamRegistry.reset();
        RuntimeParamRegistry.setStartupValue(descriptor, 1.25, RuntimeParamSource.STARTUP_CONFIG);

        assertFalse(RuntimeParamRegistry.isBootCompleted());
        assertEquals(1.25, RuntimeParamRegistry.getDouble("ui.scale"), 0.001);
        assertNull(changedValue.get());
    }
}
