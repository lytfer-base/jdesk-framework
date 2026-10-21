package com.jdesk.demo;

import javax.swing.*;

/**
 * Extension point contract for tool window providers.
 */
@FunctionalInterface
public interface ToolWindowProvider {
    JComponent createComponent();
}
