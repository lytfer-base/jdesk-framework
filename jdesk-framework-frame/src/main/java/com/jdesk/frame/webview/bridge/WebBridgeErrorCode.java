package com.jdesk.frame.webview.bridge;

/**
 * Error types of the WebBridge protocol.
 * Names are serialized to pages: if you add or rename one, check page compatibility.
 */
public enum WebBridgeErrorCode {
    INVALID_REQUEST,
    METHOD_NOT_FOUND,
    HANDLER_FAILED,
    BRIDGE_DISPOSED
}
