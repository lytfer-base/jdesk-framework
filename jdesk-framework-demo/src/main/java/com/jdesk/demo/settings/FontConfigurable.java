package com.jdesk.demo.settings;

import com.jdesk.frame.settings.SearchableConfigurable;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

/**
 * Font settings — family, size.
 */
public class FontConfigurable implements SearchableConfigurable {

    private JComboBox<String> fontFamilyCombo;
    private JSpinner fontSizeSpinner;

    private String fontFamily = "SansSerif";
    private int fontSize = 13;
    private String savedFontFamily = fontFamily;
    private int savedFontSize = fontSize;

    @Override
    public String getDisplayName() {
        return "Font";
    }

    @Override
    public String getDescription() {
        return "Configure the default font used throughout the application.";
    }

    @Override
    public Set<String> getSearchKeywords() {
        return Set.of("text", "size", "family", "typography", "monospace", "sans", "serif");
    }

    @Override
    public JComponent createComponent() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JdfUi.empty(8, 0, 8, 0));

        // Font family
        JPanel familyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JdfScale.scale(8), 0));
        familyRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, JdfScale.scale(36)));
        familyRow.add(new JLabel("Font family:"));
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        fontFamilyCombo = new JComboBox<>(fonts);
        fontFamilyCombo.setSelectedItem(fontFamily);
        fontFamilyCombo.setPreferredSize(JdfUi.size(200, 26));
        familyRow.add(fontFamilyCombo);
        panel.add(familyRow);
        panel.add(JdfUi.verticalStrut(12));

        // Font size
        JPanel sizeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JdfScale.scale(8), 0));
        sizeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, JdfScale.scale(36)));
        sizeRow.add(new JLabel("Font size:"));
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(fontSize, 8, 32, 1));
        fontSizeSpinner.setPreferredSize(JdfUi.size(60, 26));
        sizeRow.add(fontSizeSpinner);
        panel.add(sizeRow);
        panel.add(JdfUi.verticalStrut(16));

        // Preview
        JLabel previewLabel = new JLabel("The quick brown fox jumps over the lazy dog.");
        previewLabel.setFont(new Font(fontFamily, Font.PLAIN, JdfScale.scaleFontSize(fontSize)));
        previewLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        previewLabel.setBorder(JdfUi.empty(8));
        panel.add(new JLabel("Preview:"));
        panel.add(previewLabel);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    @Override
    public boolean isModified() {
        return !getCurrentFamily().equals(savedFontFamily) || getCurrentSize() != savedFontSize;
    }

    private String getCurrentFamily() {
        return fontFamilyCombo != null ? (String) fontFamilyCombo.getSelectedItem() : fontFamily;
    }

    private int getCurrentSize() {
        return fontSizeSpinner != null ? (Integer) fontSizeSpinner.getValue() : fontSize;
    }

    @Override
    public void apply() {
        fontFamily = getCurrentFamily();
        fontSize = getCurrentSize();
        savedFontFamily = fontFamily;
        savedFontSize = fontSize;
    }

    @Override
    public void reset() {
        fontFamily = savedFontFamily;
        fontSize = savedFontSize;
        if (fontFamilyCombo != null) fontFamilyCombo.setSelectedItem(fontFamily);
        if (fontSizeSpinner != null) fontSizeSpinner.setValue(fontSize);
    }
}
