package com.jdesk.frame.settings;

import com.jdesk.core.runtime.RuntimeParamDescriptor;
import com.jdesk.core.runtime.RuntimeParamMutability;
import com.jdesk.core.runtime.RuntimeParamType;

import java.util.Objects;

/**
 * Binds a user setting to a core runtime parameter; {@link SettingsManager} updates {@code RuntimeParamRegistry}
 * when the setting is registered or changed by the user.
 */
public final class RuntimeSettingBinding {

    private final RuntimeParamDescriptor descriptor;

    /**
     * @deprecated Use {@link #RuntimeSettingBinding(RuntimeParamDescriptor)} instead.
     */
    @Deprecated
    public RuntimeSettingBinding(String runtimeKey, RuntimeParamMutability mutability) {
        if (runtimeKey == null || runtimeKey.isBlank()) {
            throw new IllegalArgumentException("runtimeKey cannot be blank");
        }
        this.descriptor = RuntimeParamDescriptor.builder(runtimeKey, RuntimeParamType.STRING)
                .mutability(Objects.requireNonNull(mutability, "mutability"))
                .build();
    }

    public RuntimeSettingBinding(RuntimeParamDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    /**
     * @deprecated Use {@link #of(RuntimeParamDescriptor)} instead.
     */
    @Deprecated
    public static RuntimeSettingBinding of(String runtimeKey, RuntimeParamMutability mutability) {
        return new RuntimeSettingBinding(runtimeKey, mutability);
    }

    public static RuntimeSettingBinding of(RuntimeParamDescriptor descriptor) {
        return new RuntimeSettingBinding(descriptor);
    }

    public RuntimeParamDescriptor getDescriptor() {
        return descriptor;
    }

    public String getRuntimeKey() {
        return descriptor.getKey();
    }

    public RuntimeParamMutability getMutability() {
        return descriptor.getMutability();
    }
}
