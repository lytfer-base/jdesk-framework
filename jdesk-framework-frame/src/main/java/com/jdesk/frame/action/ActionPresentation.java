package com.jdesk.frame.action;

import javax.swing.Icon;
import javax.swing.KeyStroke;
import java.util.Objects;

/**
 * Mutable presentation of an {@link AnAction}. Each Swing menu item or button gets its own copy, so an update can
 * change text, icon, shortcut, enablement and visibility without touching the action's template.
 */
public final class ActionPresentation {

    /** Swing {@code Action} property that holds the visibility; menu items and buttons read it to show or hide. */
    public static final String VISIBLE_KEY = "Jdf.Action.visible";

    private String text;
    private String description;
    private Icon icon;
    private KeyStroke shortcut;
    private boolean enabled = true;
    private boolean visible = true;

    public ActionPresentation(String text, String description, Icon icon, KeyStroke shortcut) {
        this.text = Objects.requireNonNull(text, "text");
        this.description = description;
        this.icon = icon;
        this.shortcut = shortcut;
    }

    public ActionPresentation copy() {
        ActionPresentation copy = new ActionPresentation(text, description, icon, shortcut);
        copy.enabled = enabled;
        copy.visible = visible;
        return copy;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Icon getIcon() {
        return icon;
    }

    public void setIcon(Icon icon) {
        this.icon = icon;
    }

    public KeyStroke getShortcut() {
        return shortcut;
    }

    public void setShortcut(KeyStroke shortcut) {
        this.shortcut = shortcut;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
