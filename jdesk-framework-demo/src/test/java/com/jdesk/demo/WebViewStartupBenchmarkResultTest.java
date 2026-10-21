package com.jdesk.demo;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WebViewStartupBenchmarkResultTest {

    @Test
    void benchmarkPanelDoesNotAutoRunWhenCreated() throws Exception {
        AtomicReference<JComponent> panelRef = new AtomicReference<>();

        runOnEdtAndWait(() -> panelRef.set(WebViewStartupBenchmarkPanel.create(null)));
        runOnEdtAndWait(() -> {
        });

        JTextArea resultsArea = findTextArea(panelRef.get());
        assertNotNull(resultsArea);
        assertTrue(resultsArea.getText().contains("WebView startup benchmark."));
        assertFalse(resultsArea.getText().contains("Running WebView startup benchmark..."));
    }

    private static void runOnEdtAndWait(Runnable runnable) throws InvocationTargetException, InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeAndWait(runnable);
        }
    }

    private static JTextArea findTextArea(Component component) {
        if (component instanceof JTextArea textArea) {
            return textArea;
        }
        if (component instanceof JScrollPane scrollPane) {
            return findTextArea(scrollPane.getViewport().getView());
        }
        if (component instanceof JSplitPane splitPane) {
            JTextArea left = findTextArea(splitPane.getLeftComponent());
            return left != null ? left : findTextArea(splitPane.getRightComponent());
        }
        if (component instanceof JPanel panel) {
            return findTextAreaInContainer(panel);
        }
        if (component instanceof Container container) {
            return findTextAreaInContainer(container);
        }
        return null;
    }

    private static JTextArea findTextAreaInContainer(Container container) {
        for (Component child : container.getComponents()) {
            JTextArea textArea = findTextArea(child);
            if (textArea != null) {
                return textArea;
            }
        }
        return null;
    }
}
