package com.jdesk.frame.ui;

import com.jdesk.frame.laf.JdfColors;

import javax.swing.JLayeredPane;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.JToolBar;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager2;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root pane of JDesk windows: above the usual menu bar, toolbar and content it reserves a header row and optional
 * north extensions, stacked top to bottom, with the remaining space going to the content pane.
 */
public class JdfRootPane extends JRootPane {

    private JdfHeader header;
    private JToolBar toolBar;

    /** Extra rows at the top, replaced by key and laid out in registration order. */
    private final Map<String, JComponent> northExtensions = new LinkedHashMap<>();

    public JdfRootPane() {
        super();
        setOpaque(true);
    }

    public void installHeader(JdfHeader header) {
        if (this.header != null) {
            getLayeredPane().remove(this.header);
        }
        this.header = header;
        if (header != null) {
            getLayeredPane().add(header, JLayeredPane.DEFAULT_LAYER);
        }
        revalidate();
        repaint();
    }

    public JdfHeader getHeader() {
        return header;
    }

    public void setToolBar(JToolBar toolBar) {
        if (this.toolBar != null) {
            getLayeredPane().remove(this.toolBar);
        }
        this.toolBar = toolBar;
        if (toolBar != null) {
            getLayeredPane().add(toolBar, JLayeredPane.DEFAULT_LAYER);
        }
        revalidate();
        repaint();
    }

    public JToolBar getToolBar() {
        return toolBar;
    }

    public void addNorthExtension(String key, JComponent component) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key cannot be blank");
        }
        removeNorthExtension(key);
        if (component != null) {
            northExtensions.put(key, component);
            getLayeredPane().add(component, JLayeredPane.DEFAULT_LAYER);
        }
        revalidate();
        repaint();
    }

    public JComponent removeNorthExtension(String key) {
        JComponent removed = northExtensions.remove(key);
        if (removed != null) {
            getLayeredPane().remove(removed);
            revalidate();
            repaint();
        }
        return removed;
    }

    public JComponent getNorthExtension(String key) {
        return northExtensions.get(key);
    }

    public void updateTheme() {
        setBackground(JdfColors.background());
        if (header != null) {
            header.updateTheme();
        }
    }

    @Override
    protected LayoutManager2 createRootLayout() {
        return new JdfRootLayout();
    }

    /** Stacks the header, menu bar, toolbar, extensions and content vertically. */
    private final class JdfRootLayout implements LayoutManager2, Serializable {

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = getInsets();
            int width = Math.max(0, getWidth() - insets.left - insets.right);
            int height = Math.max(0, getHeight() - insets.top - insets.bottom);

            getLayeredPane().setBounds(insets.left, insets.top, width, height);
            getGlassPane().setBounds(insets.left, insets.top, width, height);

            int y = 0;
            y = layoutRow(header, width, y);
            y = layoutRow(getJMenuBar(), width, y);
            y = layoutRow(toolBar, width, y);
            for (JComponent extension : northExtensions.values()) {
                y = layoutRow(extension, width, y);
            }

            Container contentPane = getContentPane();
            if (contentPane != null) {
                contentPane.setBounds(0, y, width, Math.max(0, height - y));
            }
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return computeLayoutSize(SizeType.PREFERRED);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return computeLayoutSize(SizeType.MINIMUM);
        }

        @Override
        public Dimension maximumLayoutSize(Container target) {
            return computeLayoutSize(SizeType.MAXIMUM);
        }

        @Override
        public void addLayoutComponent(Component component, Object constraints) {
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }

        @Override
        public float getLayoutAlignmentX(Container target) {
            return 0.5f;
        }

        @Override
        public float getLayoutAlignmentY(Container target) {
            return 0.5f;
        }

        @Override
        public void invalidateLayout(Container target) {
        }

        private int layoutRow(Component component, int width, int y) {
            if (component == null || !component.isVisible()) {
                return y;
            }
            int height = Math.max(0, component.getPreferredSize().height);
            component.setBounds(0, y, width, height);
            return y + height;
        }

        private Dimension computeLayoutSize(SizeType type) {
            Insets insets = getInsets();
            Dimension result = new Dimension(insets.left + insets.right, insets.top + insets.bottom);
            append(result, header, type);
            append(result, getJMenuBar(), type);
            append(result, toolBar, type);
            for (JComponent extension : northExtensions.values()) {
                append(result, extension, type);
            }
            append(result, getContentPane(), type);
            return result;
        }

        private void append(Dimension result, Component component, SizeType type) {
            if (component == null || !component.isVisible()) {
                return;
            }
            Dimension size = switch (type) {
                case MINIMUM -> component.getMinimumSize();
                case MAXIMUM -> component.getMaximumSize();
                default -> component.getPreferredSize();
            };
            result.width = Math.max(result.width, size.width);
            result.height += size.height;
        }
    }

    private enum SizeType {
        MINIMUM,
        PREFERRED,
        MAXIMUM
    }
}
