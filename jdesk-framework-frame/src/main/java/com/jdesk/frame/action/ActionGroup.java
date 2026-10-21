package com.jdesk.frame.action;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A named group of {@link AnAction}s, separators and subgroups, rendered as a Swing menu or a toolbar section.
 * Subgroups become submenus in a menu and are flattened into a toolbar.
 */
public class ActionGroup {

    private final String id;
    private final String displayName;

    private final List<Object> children = new ArrayList<>(); // AnAction | ActionGroup | "SEPARATOR"

    /** When set, the menu leaves out separators. */
    private boolean compact = false;

    public ActionGroup(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }

    public ActionGroup setCompact(boolean compact) {
        this.compact = compact;
        return this;
    }

    // --- Children ---

    public ActionGroup addAction(AnAction action) {
        children.add(action);
        return this;
    }

    public ActionGroup addSeparator() {
        children.add("SEPARATOR");
        return this;
    }

    public ActionGroup addSubGroup(ActionGroup group) {
        children.add(group);
        return this;
    }

    /** Returns every action in the group as a flat list, expanding subgroups and skipping separators. */
    @SuppressWarnings("unchecked")
    public List<AnAction> getActions() {
        List<AnAction> result = new ArrayList<>();
        for (Object child : children) {
            if (child instanceof AnAction) {
                result.add((AnAction) child);
            } else if (child instanceof ActionGroup) {
                result.addAll(((ActionGroup) child).getActions());
            }
        }
        return result;
    }

    // --- Swing menu ---

    /** Builds a {@link JMenu} from the children in order, letting each action update its presentation first. */
    public JMenu buildMenu(ActionContext context) {
        JMenu menu = new JMenu(displayName);
        for (Object child : children) {
            if (child instanceof AnAction action) {
                javax.swing.Action swingAction = action.toSwingAction(context);
                JMenuItem item = new JMenuItem(swingAction);
                item.setVisible(!Boolean.FALSE.equals(swingAction.getValue(ActionPresentation.VISIBLE_KEY)));
                menu.add(item);
            } else if (child instanceof ActionGroup group) {
                menu.add(group.buildMenu(context));
            } else if ("SEPARATOR".equals(child)) {
                if (!compact) menu.addSeparator();
            }
        }
        return menu;
    }

    public int size() { return children.size(); }
}
