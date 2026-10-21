package com.jdesk.demo.settings;

import com.jdesk.frame.settings.SearchableConfigurable;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

/**
 * General settings — application name, startup behavior.
 */
public class GeneralSettingsConfigurable implements SearchableConfigurable {

    private JTextField appNameField;
    private JCheckBox autoStartCheckbox;
    private JCheckBox showWelcomeCheckbox;

    private String appName = "JDesk Framework Demo";
    private boolean autoStart = false;
    private boolean showWelcome = true;

    private String savedAppName = appName;
    private boolean savedAutoStart = autoStart;
    private boolean savedShowWelcome = showWelcome;

    @Override
    public String getDisplayName() {
        return "General";
    }

    @Override
    public String getDescription() {
        return "Configure general application behavior and startup options.";
    }

    @Override
    public Set<String> getSearchKeywords() {
        return Set.of("startup", "launch", "name", "title", "welcome", "general", "auto");
    }

    @Override
    public JComponent createComponent() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JdfUi.empty(8, 0, 8, 0));

        // Application name
        JPanel nameRow = new JPanel(new BorderLayout(JdfScale.scale(8), 0));
        nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, JdfScale.scale(32)));
        nameRow.add(new JLabel("Application name:"));
        appNameField = new JTextField(appName, 20);
        nameRow.add(appNameField, BorderLayout.EAST);
        panel.add(nameRow);
        panel.add(JdfUi.verticalStrut(12));

        // Startup section
        JLabel startupLabel = new JLabel("Startup");
        startupLabel.setFont(startupLabel.getFont().deriveFont(Font.BOLD));
        panel.add(startupLabel);
        panel.add(JdfUi.verticalStrut(6));

        autoStartCheckbox = new JCheckBox("Auto-start on system login");
        autoStartCheckbox.setSelected(autoStart);
        autoStartCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(autoStartCheckbox);
        panel.add(JdfUi.verticalStrut(6));

        showWelcomeCheckbox = new JCheckBox("Show welcome screen on startup");
        showWelcomeCheckbox.setSelected(showWelcome);
        showWelcomeCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(showWelcomeCheckbox);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    @Override
    public boolean isModified() {
        return !appName.equals(savedAppName)
                || autoStart != savedAutoStart
                || showWelcome != savedShowWelcome;
    }

    @Override
    public void apply() {
        if (appNameField != null) appName = appNameField.getText();
        if (autoStartCheckbox != null) autoStart = autoStartCheckbox.isSelected();
        if (showWelcomeCheckbox != null) showWelcome = showWelcomeCheckbox.isSelected();

        savedAppName = appName;
        savedAutoStart = autoStart;
        savedShowWelcome = showWelcome;
    }

    @Override
    public void reset() {
        appName = savedAppName;
        autoStart = savedAutoStart;
        showWelcome = savedShowWelcome;

        if (appNameField != null) appNameField.setText(appName);
        if (autoStartCheckbox != null) autoStartCheckbox.setSelected(autoStart);
        if (showWelcomeCheckbox != null) showWelcomeCheckbox.setSelected(showWelcome);
    }

    // Accessors for demo display
    public String getAppName() { return appName; }
    public boolean isAutoStart() { return autoStart; }
    public boolean isShowWelcome() { return showWelcome; }
}
