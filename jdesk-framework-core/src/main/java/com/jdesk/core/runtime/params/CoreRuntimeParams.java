package com.jdesk.core.runtime.params;

import com.jdesk.core.runtime.RuntimeParamDescriptor;
import com.jdesk.core.runtime.RuntimeParamType;

/**
 * Core module runtime parameter declarations.
 */
public final class CoreRuntimeParams {

    public static final String STARTUP_TRACE_MODE_KEY = "jdesk.startup.trace.mode";
    public static final String PLUGIN_PARENT_FIRST_PACKAGES_KEY = "jdesk.plugin.parentFirstPackages";

    public static final RuntimeParamDescriptor STARTUP_TRACE_MODE =
            RuntimeParamDescriptor.builder(STARTUP_TRACE_MODE_KEY, RuntimeParamType.STRING)
                    .description("Startup trace mode: report, time, or false")
                    .build();

    public static final RuntimeParamDescriptor PLUGIN_PARENT_FIRST_PACKAGES =
            RuntimeParamDescriptor.builder(PLUGIN_PARENT_FIRST_PACKAGES_KEY, RuntimeParamType.STRING)
                    .description("Additional plugin package prefixes that must be loaded from the parent class loader.")
                    .build();

    private CoreRuntimeParams() {
    }
}
