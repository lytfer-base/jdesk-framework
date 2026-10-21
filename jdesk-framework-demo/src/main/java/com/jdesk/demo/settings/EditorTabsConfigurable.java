package com.jdesk.demo.settings;

import com.jdesk.frame.settings.SearchableConfigurable;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

/**
 * Editor tab settings — placement, close button behavior.
 */
public class EditorTabsConfigurable implements SearchableConfigurable {

    private JComboBox<String> tabPlacementCombo;
    private JCheckBox showCloseButtonCheckbox;
    private JCheckBox showFileIconCheckbox;

    private String tabPlacement = "Top";
    private boolean showCloseButton = true;
    private boolean showFileIcon = true;
    private String savedTabPlacement = tabPlacement;
    private boolean savedShowCloseButton = showCloseButton;
    private boolean savedShowFileIcon = showFileIcon;

    @Override
    public String getDisplayName() {
        return "Tabs";
    }

    @Override
    public String getDescription() {
        return "Configure editor tab appearance and placement.";
    }

    @Override
    public Set<String> getSearchKeywords() {
        return Set.of("tab", "placement", "close", "icon", "document", "editor", "pane");
    }

    @Override
    public JComponent createComponent() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JdfUi.empty(8, 0, 8, 0));

        // Tab placement
        JPanel placementRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JdfScale.scale(8), 0));
        placementRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, JdfScale.scale(36)));
        placementRow.add(new JLabel("Tab placement:"));
        tabPlacementCombo = new JComboBox<>(new String[]{"Top", "Bottom", "Left", "Right"});
        tabPlacementCombo.setSelectedItem(tabPlacement);
        placementRow.add(tabPlacementCombo);
        panel.add(placementRow);
        panel.add(JdfUi.verticalStrut(12));

        // Options
        JLabel optionsLabel = new JLabel("Options");
        optionsLabel.setFont(optionsLabel.getFont().deriveFont(Font.BOLD));
        optionsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(optionsLabel);
        panel.add(JdfUi.verticalStrut(6));

        showCloseButtonCheckbox = new JCheckBox("Show close button on tabs");
        showCloseButtonCheckbox.setSelected(showCloseButton);
        showCloseButtonCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(showCloseButtonCheckbox);
        panel.add(JdfUi.verticalStrut(4));

        showFileIconCheckbox = new JCheckBox("Show file type icon on tabs");
        showFileIconCheckbox.setSelected(showFileIcon);
        showFileIconCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(showFileIconCheckbox);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    @Override
    public boolean isModified() {
        return !getCurrentPlacement().equals(savedTabPlacement)
                || showCloseButton != savedShowCloseButton
                || showFileIcon != savedShowFileIcon;
    }

    private String getCurrentPlacement() {
        return tabPlacementCombo != null ? (String) tabPlacementCombo.getSelectedItem() : tabPlacement;
    }

    @Override
    public void apply() {
        tabPlacement = getCurrentPlacement();
        showCloseButton = showCloseButtonCheckbox != null && showCloseButtonCheckbox.isSelected();
        showFileIcon = showFileIconCheckbox != null && showFileIconCheckbox.isSelected();
        savedTabPlacement = tabPlacement;
        savedShowCloseButton = showCloseButton;
        savedShowFileIcon = showFileIcon;
    }

    @Override
    public void reset() {
        tabPlacement = savedTabPlacement;
        showCloseButton = savedShowCloseButton;
        showFileIcon = savedShowFileIcon;
        if (tabPlacementCombo != null) tabPlacementCombo.setSelectedItem(tabPlacement);
        if (showCloseButtonCheckbox != null) showCloseButtonCheckbox.setSelected(showCloseButton);
        if (showFileIconCheckbox != null) showFileIconCheckbox.setSelected(showFileIcon);
    }
}
