package com.jdesk.demo.settings;

import com.jdesk.frame.settings.SearchableConfigurable;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

/**
 * Editor spacing settings — indentation, line spacing.
 */
public class EditorSpacingConfigurable implements SearchableConfigurable {

    private JSpinner indentSpinner;
    private JCheckBox useTabsCheckbox;
    private JSpinner lineSpacingSpinner;

    private int indentSize = 4;
    private boolean useTabs = false;
    private double lineSpacing = 1.2;
    private int savedIndentSize = indentSize;
    private boolean savedUseTabs = useTabs;
    private double savedLineSpacing = lineSpacing;

    @Override
    public String getDisplayName() {
        return "Spacing";
    }

    @Override
    public String getDescription() {
        return "Configure indentation and line spacing for text editors.";
    }

    @Override
    public Set<String> getSearchKeywords() {
        return Set.of("indent", "tab", "space", "line", "spacing", "margin", "padding");
    }

    @Override
    public JComponent createComponent() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JdfUi.empty(8, 0, 8, 0));

        // Indentation
        JLabel indentLabel = new JLabel("Indentation");
        indentLabel.setFont(indentLabel.getFont().deriveFont(Font.BOLD));
        indentLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(indentLabel);
        panel.add(JdfUi.verticalStrut(6));

        JPanel indentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JdfScale.scale(8), 0));
        indentRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, JdfScale.scale(36)));
        indentRow.add(new JLabel("Indent size:"));
        indentSpinner = new JSpinner(new SpinnerNumberModel(indentSize, 1, 16, 1));
        indentSpinner.setPreferredSize(JdfUi.size(60, 26));
        indentRow.add(indentSpinner);
        panel.add(indentRow);
        panel.add(JdfUi.verticalStrut(4));

        useTabsCheckbox = new JCheckBox("Use tab character instead of spaces");
        useTabsCheckbox.setSelected(useTabs);
        useTabsCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(useTabsCheckbox);
        panel.add(JdfUi.verticalStrut(16));

        // Line spacing
        JLabel spacingLabel = new JLabel("Line Spacing");
        spacingLabel.setFont(spacingLabel.getFont().deriveFont(Font.BOLD));
        spacingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(spacingLabel);
        panel.add(JdfUi.verticalStrut(6));

        JPanel spacingRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JdfScale.scale(8), 0));
        spacingRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, JdfScale.scale(36)));
        spacingRow.add(new JLabel("Line spacing:"));
        lineSpacingSpinner = new JSpinner(
                new SpinnerNumberModel(lineSpacing, 0.8, 2.5, 0.1));
        lineSpacingSpinner.setPreferredSize(JdfUi.size(60, 26));
        spacingRow.add(lineSpacingSpinner);
        panel.add(spacingRow);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    @Override
    public boolean isModified() {
        return getCurrentIndent() != savedIndentSize
                || useTabs != savedUseTabs
                || Math.abs(getCurrentLineSpacing() - savedLineSpacing) > 0.01;
    }

    private int getCurrentIndent() {
        return indentSpinner != null ? (Integer) indentSpinner.getValue() : indentSize;
    }

    private double getCurrentLineSpacing() {
        return lineSpacingSpinner != null ? (Double) lineSpacingSpinner.getValue() : lineSpacing;
    }

    @Override
    public void apply() {
        indentSize = getCurrentIndent();
        useTabs = useTabsCheckbox != null && useTabsCheckbox.isSelected();
        lineSpacing = getCurrentLineSpacing();
        savedIndentSize = indentSize;
        savedUseTabs = useTabs;
        savedLineSpacing = lineSpacing;
    }

    @Override
    public void reset() {
        indentSize = savedIndentSize;
        useTabs = savedUseTabs;
        lineSpacing = savedLineSpacing;
        if (indentSpinner != null) indentSpinner.setValue(indentSize);
        if (useTabsCheckbox != null) useTabsCheckbox.setSelected(useTabs);
        if (lineSpacingSpinner != null) lineSpacingSpinner.setValue(lineSpacing);
    }
}
