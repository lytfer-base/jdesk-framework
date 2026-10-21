package com.jdesk.frame.ui;

import com.jdesk.core.i18n.I18n;
import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.ui.components.JdfStatusLabel;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compact IDEA-style status bar with left, center and right widget zones, each sorted by
 * {@link JdfStatusBarWidget#getOrder()}. The left zone starts with the info text; applications add widgets for
 * things such as run state, encoding or background tasks.
 */
public class JdfStatusBar extends JPanel {

    /** Zone a widget is shown in. */
    public enum Position {
        LEFT,
        CENTER,
        RIGHT
    }

    private final JdfStatusLabel infoLabel = new JdfStatusLabel(defaultInfoText());
    /** True until a caller sets the info text; until then the default text follows the UI language. */
    private boolean showingDefaultInfo = true;
    private final JPanel leftPanel = createZone(FlowLayout.LEFT);
    private final JPanel centerPanel = createZone(FlowLayout.CENTER);
    private final JPanel rightPanel = createZone(FlowLayout.RIGHT);

    /** Widgets by ID; adding one with an existing ID removes the old one first. */
    private final Map<String, WidgetEntry> widgets = new LinkedHashMap<>();

    public JdfStatusBar() {
        super(new BorderLayout());
        setOpaque(true);
        leftPanel.add(infoLabel);
        add(leftPanel, BorderLayout.WEST);
        add(centerPanel, BorderLayout.CENTER);
        add(rightPanel, BorderLayout.EAST);
        updateTheme();
    }

    public void setInfo(String text) {
        showingDefaultInfo = false;
        infoLabel.setText(" " + (text == null ? "" : text));
    }

    /** Resets the default info text in the current language, leaving caller-set text alone; call on the EDT. */
    void relabel() {
        if (showingDefaultInfo) {
            infoLabel.setText(defaultInfoText());
        }
    }

    public String getInfo() {
        String text = infoLabel.getText();
        return text == null ? "" : text.trim();
    }

    public void addWidget(JdfStatusBarWidget widget) {
        if (widget == null) {
            return;
        }
        removeWidget(widget.getId());
        JComponent component = widget.createComponent(this);
        WidgetEntry entry = new WidgetEntry(widget, component);
        widgets.put(widget.getId(), entry);
        rebuildZones();
    }

    public void addWidget(String id, JComponent component, Position position) {
        addWidget(new ComponentWidget(id, component, position));
    }

    public void removeWidget(String id) {
        WidgetEntry entry = widgets.remove(id);
        if (entry == null) {
            return;
        }
        Component parent = entry.component.getParent();
        if (parent instanceof JPanel panel) {
            panel.remove(entry.component);
            panel.revalidate();
            panel.repaint();
        }
    }

    public JComponent getWidget(String id) {
        WidgetEntry entry = widgets.get(id);
        return entry == null ? null : entry.component;
    }

    public JdfStatusBarWidget getStatusBarWidget(String id) {
        WidgetEntry entry = widgets.get(id);
        return entry == null ? null : entry.widget;
    }

    public void updateTheme() {
        setBackground(JdfColors.panelBackground());
        setBorder(JdfUi.topBorder());
        JdfUi.updateTreeTheme(infoLabel);
        updateZone(leftPanel);
        updateZone(centerPanel);
        updateZone(rightPanel);
        widgets.values().forEach(entry -> JdfUi.updateTreeTheme(entry.component));
    }

    private JPanel panelFor(Position position) {
        return switch (position) {
            case CENTER -> centerPanel;
            case RIGHT -> rightPanel;
            default -> leftPanel;
        };
    }

    private void updateTheme(Component component) {
        component.setForeground(JdfColors.mutedText());
        component.setBackground(JdfColors.panelBackground());
        if (component instanceof JPanel panel) {
            panel.setOpaque(false);
            for (Component child : panel.getComponents()) {
                updateTheme(child);
            }
        }
    }

    private void rebuildZones() {
        leftPanel.removeAll();
        centerPanel.removeAll();
        rightPanel.removeAll();
        leftPanel.add(infoLabel);

        widgets.values().stream()
                .sorted(Comparator.comparingInt(entry -> entry.widget.getOrder()))
                .forEach(entry -> {
                    panelFor(entry.widget.getPosition()).add(entry.component);
                    JdfUi.updateTreeTheme(entry.component);
                });

        revalidate();
        repaint();
    }

    private void updateZone(JPanel panel) {
        panel.setBackground(JdfColors.panelBackground());
        panel.setForeground(JdfColors.mutedText());
        panel.setOpaque(false);
        for (Component child : panel.getComponents()) {
            JdfUi.updateTreeTheme(child);
        }
    }

    private static String defaultInfoText() {
        return " " + I18n.message("frame.statusBar.ready");
    }

    private static JPanel createZone(int alignment) {
        JPanel panel = new JPanel(new FlowLayout(alignment, JdfScale.scale(8), JdfScale.scale(2)));
        panel.setOpaque(false);
        return panel;
    }

    private record WidgetEntry(JdfStatusBarWidget widget, JComponent component) {
    }

    /** Wraps any component as a status bar widget. */
    private static final class ComponentWidget implements JdfStatusBarWidget {

        private final String id;
        private final JComponent component;
        private final Position position;

        private ComponentWidget(String id, JComponent component, Position position) {
            this.id = id;
            this.component = component;
            this.position = position == null ? Position.RIGHT : position;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Position getPosition() {
            return position;
        }

        @Override
        public JComponent createComponent(JdfStatusBar statusBar) {
            return component;
        }
    }
}
