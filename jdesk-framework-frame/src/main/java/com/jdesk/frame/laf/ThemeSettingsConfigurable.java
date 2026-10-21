package com.jdesk.frame.laf;

import com.jdesk.core.i18n.I18n;
import com.jdesk.frame.settings.SearchableConfigurable;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Built-in settings page for choosing a JDesk theme: one radio button per theme registered in {@link JdfLafManager}
 * and a simple preview. The theme switches only on Apply; until then a selection only changes the preview.
 */
public final class ThemeSettingsConfigurable implements SearchableConfigurable {

    private final JdfLafManager lafManager;

    private final Map<String, JRadioButton> themeButtons = new LinkedHashMap<>();

    private ThemePreviewPanel previewPanel;

    public ThemeSettingsConfigurable(JdfLafManager lafManager) {
        this.lafManager = Objects.requireNonNull(lafManager, "lafManager");
    }

    @Override
    public String getDisplayName() {
        return I18n.message("frame.settings.theme.title");
    }

    @Override
    public String getDescription() {
        return I18n.message("frame.settings.theme.description");
    }

    @Override
    public Set<String> getSearchKeywords() {
        return Set.of("appearance", "theme", "laf", "look", "feel", "light", "dark", "color");
    }

    @Override
    public JComponent createComponent() {
        themeButtons.clear();

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JdfUi.empty(8, 0, 8, 0));

        JLabel label = new JLabel(I18n.message("frame.settings.theme.label"));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label);
        panel.add(JdfUi.verticalStrut(8));

        ButtonGroup group = new ButtonGroup();
        for (JdfTheme theme : lafManager.getThemes()) {
            JRadioButton button = new JRadioButton(theme.getDisplayName());
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setOpaque(false);
            button.addActionListener(e -> updatePreview());
            group.add(button);
            themeButtons.put(theme.getId(), button);
            panel.add(button);
            panel.add(JdfUi.verticalStrut(4));
        }

        previewPanel = new ThemePreviewPanel();
        previewPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(JdfUi.verticalStrut(16));
        panel.add(previewPanel);

        selectTheme(lafManager.getCurrentThemeId());
        updatePreview();

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    @Override
    public boolean isModified() {
        if (themeButtons.isEmpty()) {
            return false;
        }
        return !getSelectedThemeId().equals(lafManager.getCurrentThemeId());
    }

    @Override
    public void apply() {
        if (!themeButtons.isEmpty()) {
            lafManager.setCurrentTheme(getSelectedThemeId());
        }
    }

    @Override
    public void reset() {
        selectTheme(lafManager.getCurrentThemeId());
        updatePreview();
    }

    private String getSelectedThemeId() {
        for (Map.Entry<String, JRadioButton> entry : themeButtons.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }
        return JdfLafManager.DEFAULT_THEME_ID;
    }

    private void selectTheme(String themeId) {
        JRadioButton selected = themeButtons.get(themeId);
        if (selected == null) {
            selected = themeButtons.get(JdfLafManager.DEFAULT_THEME_ID);
        }
        if (selected != null) {
            selected.setSelected(true);
        }
    }

    private void updatePreview() {
        if (previewPanel == null) {
            return;
        }
        JdfTheme theme = lafManager.findTheme(getSelectedThemeId())
                .orElse(lafManager.getCurrentTheme());
        previewPanel.applyTheme(theme);
    }

    private static final class ThemePreviewPanel extends JPanel {

        private final JLabel titleLabel = new JLabel(I18n.message("frame.settings.theme.previewTitle"));
        private final JLabel textLabel = new JLabel(I18n.message("frame.settings.theme.previewText"));
        private final JLabel accentLabel = new JLabel(I18n.message("frame.settings.theme.previewAccent"));

        ThemePreviewPanel() {
            super(new BorderLayout(JdfScale.scale(10), JdfScale.scale(8)));
            setPreferredSize(JdfUi.size(360, 96));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, JdfScale.scale(96)));

            JPanel textPanel = new JPanel();
            textPanel.setOpaque(false);
            textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
            titleLabel.setFont(JdfFonts.derive(titleLabel.getFont(), Font.BOLD, 14f));
            textPanel.add(titleLabel);
            textPanel.add(JdfUi.verticalStrut(5));
            textPanel.add(textLabel);

            accentLabel.setOpaque(true);
            accentLabel.setHorizontalAlignment(JLabel.CENTER);
            accentLabel.setPreferredSize(JdfUi.size(96, 34));

            add(textPanel, BorderLayout.CENTER);
            add(accentLabel, BorderLayout.EAST);
        }

        void applyTheme(JdfTheme theme) {
            JdfThemePalette palette = theme.getPalette();
            Color card = palette.getColor(JdfThemePalette.CARD_BACKGROUND);
            Color border = palette.getColor(JdfThemePalette.BORDER);
            Color text = palette.getColor(JdfThemePalette.TEXT);
            Color mutedText = palette.getColor(JdfThemePalette.MUTED_TEXT);
            Color accent = palette.getColor(JdfThemePalette.ACCENT);
            Color accentForeground = palette.getColor(JdfThemePalette.ACCENT_FOREGROUND);

            setBackground(card);
            setBorder(BorderFactory.createCompoundBorder(
                    JdfUi.lineBorder(border),
                    JdfUi.empty(16)));
            titleLabel.setForeground(text);
            textLabel.setForeground(mutedText);
            accentLabel.setBackground(accent);
            accentLabel.setForeground(accentForeground);
            repaint();
        }
    }
}
