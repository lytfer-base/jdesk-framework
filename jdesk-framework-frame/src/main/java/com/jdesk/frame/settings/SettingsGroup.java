package com.jdesk.frame.settings;

import javax.swing.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * A group of settings pages, shown as a node in the settings tree. Groups nest and may hold pages directly, in the
 * order added. The name is read from its supplier on every call, so {@code () -> I18n.message(key)} follows the UI
 * language.
 */
public class SettingsGroup {

    private final String id;
    private final Supplier<String> displayName;
    private final Icon icon;
    private final List<Object> children = new ArrayList<>(); // Configurable | SettingsGroup

    public SettingsGroup(String id, String displayName) {
        this(id, displayName, null);
    }

    public SettingsGroup(String id, String displayName, Icon icon) {
        this(id, () -> displayName, icon);
    }

    public SettingsGroup(String id, Supplier<String> displayName) {
        this(id, displayName, null);
    }

    public SettingsGroup(String id, Supplier<String> displayName, Icon icon) {
        this.id = id;
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.icon = icon;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName.get(); }
    public Icon getIcon() { return icon; }
    public List<Object> getChildren() { return Collections.unmodifiableList(children); }

    public SettingsGroup add(Configurable configurable) {
        children.add(configurable);
        return this;
    }

    public SettingsGroup addGroup(SettingsGroup group) {
        children.add(group);
        return this;
    }

    /** Returns the pages in this group and all its subgroups. */
    public List<Configurable> collectConfigurables() {
        List<Configurable> result = new ArrayList<>();
        for (Object child : children) {
            if (child instanceof Configurable c) {
                result.add(c);
            } else if (child instanceof SettingsGroup g) {
                result.addAll(g.collectConfigurables());
            }
        }
        return result;
    }
}
