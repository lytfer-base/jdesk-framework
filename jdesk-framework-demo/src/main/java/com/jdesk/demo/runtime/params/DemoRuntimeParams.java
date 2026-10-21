package com.jdesk.demo.runtime.params;

import com.jdesk.core.runtime.RuntimeParamDescriptor;
import com.jdesk.core.runtime.RuntimeParamMutability;
import com.jdesk.core.runtime.RuntimeParamType;

/**
 * Demo module runtime parameter declarations.
 */
public final class DemoRuntimeParams {

    public static final String TOOLBAR_UI_DEFAULT_HEIGHT_KEY = "toolbar.ui.default.height";

    public static final RuntimeParamDescriptor TOOLBAR_UI_DEFAULT_HEIGHT =
            RuntimeParamDescriptor.builder(TOOLBAR_UI_DEFAULT_HEIGHT_KEY, RuntimeParamType.INTEGER)
                    .description("Default height used by toolbar-like surfaces.")
                    .mutability(RuntimeParamMutability.MUTABLE)
                    .build();

    private DemoRuntimeParams() {
    }
}
