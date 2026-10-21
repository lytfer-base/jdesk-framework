package com.jdesk.frame.i18n;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.I18nStartup;
import com.jdesk.core.i18n.LanguageNegotiator;
import com.jdesk.core.i18n.LocaleSettings;
import com.jdesk.frame.settings.Configurable;
import com.jdesk.frame.ui.components.JdfUi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Language page of the settings dialog: writes locale.properties and switches the UI language at once. */
public final class LanguageSettingsConfigurable implements Configurable {
    private static final Logger LOG = LoggerFactory.getLogger(LanguageSettingsConfigurable.class);
    private JComboBox<Option> combo;
    private String saved = LanguageNegotiator.SYSTEM;

    @Override
    public String getDisplayName() {
        return I18n.message("frame.settings.language.title");
    }

    /** Rebuilds the options in the current language on every open and selects the saved setting. */
    @Override
    public JComponent createComponent() {
        combo = new JComboBox<>(options().toArray(Option[]::new));
        reset();
        JLabel label = new JLabel(I18n.message("frame.settings.language.title"));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Don't put the combo box straight into the BoxLayout: it would stretch across the page.
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(combo);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JdfUi.empty(8, 0, 8, 0));
        panel.add(label);
        panel.add(JdfUi.verticalStrut(8));
        panel.add(row);
        return panel;
    }

    @Override
    public boolean isModified() {
        return !Objects.equals(selectedSetting(), saved);
    }

    /** Saves the setting, then switches the language; a failed save is only logged and the switch still happens. */
    @Override
    public void apply() {
        String setting = selectedSetting();
        try {
            LocaleSettings.write(PathManager.getConfigDir(), setting);
        } catch (IOException e) {
            LOG.warn("locale.settings.write.failed setting={}", setting, e);
        }
        saved = setting;
        I18n.switchTo(LanguageNegotiator.negotiate(setting, I18nStartup.osLanguages()));
    }

    @Override
    public void reset() {
        select(LocaleSettings.read(PathManager.getConfigDir()));
        // Compare against the selected option, so a saved value with no matching option doesn't count as a change.
        saved = selectedSetting();
    }

    List<Option> options() {
        List<Option> options = new ArrayList<>(List.of(
                new Option(LanguageNegotiator.SYSTEM, I18n.message("frame.settings.language.followSystem")),
                new Option("en", I18n.message("frame.settings.language.en")),
                new Option("zh-CN", I18n.message("frame.settings.language.zhCN")),
                new Option("ja", I18n.message("frame.settings.language.ja"))));
        if (Boolean.getBoolean("jdesk.i18n.pseudo")) {
            options.add(new Option("en-XA", "Pseudo (en-XA)"));
        }
        return options;
    }

    void select(String setting) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).setting().equalsIgnoreCase(setting)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    String selectedSetting() {
        Option option = (Option) combo.getSelectedItem();
        return option == null ? LanguageNegotiator.SYSTEM : option.setting();
    }

    record Option(String setting, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
