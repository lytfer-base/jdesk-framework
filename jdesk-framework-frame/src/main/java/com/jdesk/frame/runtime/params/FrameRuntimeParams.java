package com.jdesk.frame.runtime.params;

import com.jdesk.core.runtime.RuntimeParamDescriptor;
import com.jdesk.core.runtime.RuntimeParamMutability;
import com.jdesk.core.runtime.RuntimeParamType;

/**
 * Frame module runtime parameter declarations.
 */
public final class FrameRuntimeParams {

    public static final String FRAME_UI_COMPACT_MODE_KEY = "frame.ui.compactMode";
    public static final String FRAME_UI_THEME_KEY = "frame.ui.theme";
    public static final String UI_SCALE_KEY = "jdesk.ui.scale";
    public static final String UI_SCALE_VERBOSE_KEY = "jdesk.ui.scale.verbose";
    public static final String WEBVIEW_WARMUP_KEY = "jdesk.webview.warmup";
    public static final String WEBVIEW_BACKEND_KEY = "jdesk.webview.backend";
    public static final String MCP_PORT_KEY = "frame.mcp.port";

    public static final RuntimeParamDescriptor FRAME_UI_COMPACT_MODE =
            RuntimeParamDescriptor.builder(FRAME_UI_COMPACT_MODE_KEY, RuntimeParamType.BOOLEAN)
                    .description("Use denser spacing for frame-level UI surfaces.")
                    .mutability(RuntimeParamMutability.MUTABLE)
                    .build();

    public static final RuntimeParamDescriptor FRAME_UI_THEME =
            RuntimeParamDescriptor.builder(FRAME_UI_THEME_KEY, RuntimeParamType.STRING)
                    .description("Preferred UI theme key exposed to runtime code.")
                    .mutability(RuntimeParamMutability.MUTABLE)
                    .build();

    public static final RuntimeParamDescriptor UI_SCALE =
            RuntimeParamDescriptor.builder(UI_SCALE_KEY, RuntimeParamType.STRING)
                    .description("User UI scale factor or auto.")
                    .build();

    public static final RuntimeParamDescriptor UI_SCALE_VERBOSE =
            RuntimeParamDescriptor.builder(UI_SCALE_VERBOSE_KEY, RuntimeParamType.BOOLEAN)
                    .description("Log verbose UI scale detection details.")
                    .build();

    public static final RuntimeParamDescriptor WEBVIEW_WARMUP =
            RuntimeParamDescriptor.builder(WEBVIEW_WARMUP_KEY, RuntimeParamType.STRING)
                    .description("WebView startup warmup mode: none, engine, or view.")
                    .build();

    public static final RuntimeParamDescriptor WEBVIEW_BACKEND =
            RuntimeParamDescriptor.builder(WEBVIEW_BACKEND_KEY, RuntimeParamType.STRING)
                    .description("WebView backend: auto, jcef, or jxbrowser.")
                    .build();

    public static final RuntimeParamDescriptor MCP_PORT =
            RuntimeParamDescriptor.builder(MCP_PORT_KEY, RuntimeParamType.INTEGER)
                    .description("MCP server loopback listen port; absent or 0 means random.")
                    .build();

    private FrameRuntimeParams() {
    }
}
