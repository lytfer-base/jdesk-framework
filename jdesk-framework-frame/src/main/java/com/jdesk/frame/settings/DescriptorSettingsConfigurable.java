package com.jdesk.frame.settings;

import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Settings page generated from {@link SettingDescriptor}s, with Swing editors for boolean, integer, decimal, string
 * and choice settings. Editors are kept by setting ID to compare, apply and reset values.
 */
public final class DescriptorSettingsConfigurable implements SearchableConfigurable {

    private final SettingsManager settingsManager;
    private final Supplier<String> displayName;
    private final String description;

    /** Rendered in registration order. */
    private final List<SettingDescriptor> descriptors = new ArrayList<>();

    /** Editor per setting ID, refreshed whenever the component is recreated. */
    private final Map<String, JComponent> editors = new LinkedHashMap<>();

    DescriptorSettingsConfigurable(SettingsManager settingsManager,
                                   Supplier<String> displayName,
                                   String description) {
        this.settingsManager = Objects.requireNonNull(settingsManager, "settingsManager");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.description = description == null ? "" : description;
    }

    void addDescriptor(SettingDescriptor descriptor) {
        descriptors.add(Objects.requireNonNull(descriptor, "descriptor"));
    }

    @Override
    public String getDisplayName() {
        return displayName.get();
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Set<String> getSearchKeywords() {
        return descriptors.stream()
                .flatMap(descriptor -> descriptor.getKeywords().stream())
                .collect(Collectors.toSet());
    }

    @Override
    public JComponent createComponent() {
        editors.clear();

        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(JdfUi.empty(8, 0, 8, 0));

        for (SettingDescriptor descriptor : descriptors) {
            JPanel row = createRow(descriptor);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(row);
            panel.add(JdfUi.verticalStrut(10));
        }

        panel.add(javax.swing.Box.createVerticalGlue());
        return panel;
    }

    @Override
    public boolean isModified() {
        for (SettingDescriptor descriptor : descriptors) {
            JComponent editor = editors.get(descriptor.getId());
            if (editor == null) {
                continue;
            }
            String current = descriptor.format(readEditorValue(descriptor, editor));
            String saved = settingsManager.getSettingValueString(descriptor.getId());
            if (!current.equals(saved)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void apply() {
        for (SettingDescriptor descriptor : descriptors) {
            JComponent editor = editors.get(descriptor.getId());
            if (editor != null) {
                settingsManager.setSettingValue(
                        descriptor.getId(),
                        readEditorValue(descriptor, editor));
            }
        }
    }

    @Override
    public void reset() {
        for (SettingDescriptor descriptor : descriptors) {
            JComponent editor = editors.get(descriptor.getId());
            if (editor != null) {
                writeEditorValue(editor, descriptor, settingsManager.getSettingValue(descriptor.getId()));
            }
        }
    }

    private JPanel createRow(SettingDescriptor descriptor) {
        JPanel wrapper = new JPanel(new BorderLayout(JdfScale.scale(8), JdfScale.scale(2)));
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                JdfScale.scale(descriptor.getDescription().isBlank() ? 34 : 58)));

        JLabel label = new JLabel(descriptor.getDisplayName());
        label.setPreferredSize(JdfUi.size(180, 26));
        wrapper.add(label, BorderLayout.WEST);

        JComponent editor = createEditor(descriptor);
        editors.put(descriptor.getId(), editor);
        wrapper.add(editor, BorderLayout.CENTER);

        if (!descriptor.getDescription().isBlank()) {
            JLabel description = new JLabel(descriptor.getDescription());
            description.setForeground(new Color(120, 120, 120));
            description.setBorder(JdfUi.empty(2, 188, 0, 0));
            wrapper.add(description, BorderLayout.SOUTH);
        }

        return wrapper;
    }

    private JComponent createEditor(SettingDescriptor descriptor) {
        Object value = settingsManager.getSettingValue(descriptor.getId());
        return switch (descriptor.getType()) {
            case BOOLEAN -> {
                JCheckBox checkbox = new JCheckBox();
                checkbox.setSelected((Boolean) value);
                yield checkbox;
            }
            case INTEGER -> {
                int current = (Integer) value;
                int min = descriptor.getMin() == null ? Integer.MIN_VALUE : descriptor.getMin().intValue();
                int max = descriptor.getMax() == null ? Integer.MAX_VALUE : descriptor.getMax().intValue();
                int step = descriptor.getStep() == null ? 1 : Math.max(1, descriptor.getStep().intValue());
                JSpinner spinner = new JSpinner(new SpinnerNumberModel(current, min, max, step));
                spinner.setPreferredSize(JdfUi.size(90, 26));
                yield leftAligned(spinner);
            }
            case DOUBLE -> {
                double current = (Double) value;
                double min = descriptor.getMin() == null ? -Double.MAX_VALUE : descriptor.getMin();
                double max = descriptor.getMax() == null ? Double.MAX_VALUE : descriptor.getMax();
                double step = descriptor.getStep() == null ? 0.1 : descriptor.getStep();
                JSpinner spinner = new JSpinner(new SpinnerNumberModel(current, min, max, step));
                spinner.setPreferredSize(JdfUi.size(90, 26));
                yield leftAligned(spinner);
            }
            case CHOICE -> {
                JComboBox<SettingChoice> comboBox = new JComboBox<>(
                        descriptor.getChoices().toArray(new SettingChoice[0]));
                writeEditorValue(comboBox, descriptor, value);
                comboBox.setPreferredSize(JdfUi.size(180, 26));
                yield leftAligned(comboBox);
            }
            case STRING -> {
                JTextField textField = new JTextField(value.toString());
                textField.setPreferredSize(JdfUi.size(220, 26));
                yield textField;
            }
        };
    }

    private JComponent leftAligned(JComponent component) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.add(component);
        return panel;
    }

    private Object readEditorValue(SettingDescriptor descriptor, JComponent editor) {
        JComponent actual = unwrap(editor);
        return switch (descriptor.getType()) {
            case BOOLEAN -> ((JCheckBox) actual).isSelected();
            case INTEGER, DOUBLE -> ((JSpinner) actual).getValue();
            case STRING -> ((JTextField) actual).getText();
            case CHOICE -> {
                SettingChoice choice = (SettingChoice) ((JComboBox<?>) actual).getSelectedItem();
                yield choice == null ? descriptor.getDefaultValue() : choice.getValue();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private void writeEditorValue(JComponent editor, SettingDescriptor descriptor, Object value) {
        JComponent actual = unwrap(editor);
        Object normalized = descriptor.normalizeValue(value);
        switch (descriptor.getType()) {
            case BOOLEAN -> ((JCheckBox) actual).setSelected((Boolean) normalized);
            case INTEGER, DOUBLE -> ((JSpinner) actual).setValue(normalized);
            case STRING -> ((JTextField) actual).setText(normalized.toString());
            case CHOICE -> {
                JComboBox<SettingChoice> comboBox = (JComboBox<SettingChoice>) actual;
                for (int i = 0; i < comboBox.getItemCount(); i++) {
                    SettingChoice choice = comboBox.getItemAt(i);
                    if (choice.getValue().equals(normalized.toString())) {
                        comboBox.setSelectedIndex(i);
                        return;
                    }
                }
            }
        }
    }

    private JComponent unwrap(JComponent editor) {
        if (editor instanceof JPanel panel && panel.getComponentCount() == 1
                && panel.getComponent(0) instanceof JComponent child) {
            return child;
        }
        return editor;
    }
}
