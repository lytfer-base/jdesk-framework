package com.jdesk.frame.action;

import com.jdesk.core.app.Application;
import com.jdesk.core.disposer.Disposable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-wide action registry: registers actions and groups, builds menus and toolbars, and dispatches
 * shortcuts. A lightweight take on IntelliJ Platform's {@code ActionManager}: groups keep insertion order and an
 * action's shortcut is mapped when the action is registered.
 */
public class ActionManager implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(ActionManager.class);

    private final Application application;
    private final Map<String, AnAction> actions = new ConcurrentHashMap<>();
    private final Map<String, ActionGroup> groups = new LinkedHashMap<>();

    private final Map<KeyStroke, String> shortcutToActionId = new ConcurrentHashMap<>();

    public ActionManager(Application application) {
        this.application = application;
    }

    // ============== Actions ==============

    /** Registers an action together with its shortcut. */
    public void registerAction(AnAction action) {
        actions.put(action.getId(), action);
        if (action.getShortcut() != null) {
            shortcutToActionId.put(action.getShortcut(), action.getId());
        }
        LOG.debug("Registered action: {}", action.getId());
    }

    /** Registers several actions. */
    public void registerActions(AnAction... acts) {
        for (AnAction a : acts) registerAction(a);
    }

    /** Returns the action with this ID, or {@code null} with a logged warning. */
    public AnAction getAction(String id) {
        AnAction action = actions.get(id);
        if (action == null) {
            LOG.warn("Action not found: {}", id);
        }
        return action;
    }

    /** Returns an unmodifiable view of all registered actions. */
    public Collection<AnAction> getAllActions() {
        return Collections.unmodifiableCollection(actions.values());
    }

    // ============== Groups ==============

    /** Defines an action group, typically a top-level menu or a toolbar section. */
    public ActionGroup registerGroup(String groupId, String displayName) {
        ActionGroup group = new ActionGroup(groupId, displayName);
        groups.put(groupId, group);
        return group;
    }

    /** Adds an action to a group, creating the group if needed; an unknown action is only logged, never stubbed. */
    public void addToGroup(String groupId, String actionId) {
        ActionGroup group = groups.get(groupId);
        if (group == null) {
            group = registerGroup(groupId, groupId);
        }
        AnAction action = actions.get(actionId);
        if (action != null) {
            group.addAction(action);
        } else {
            LOG.warn("Cannot add unknown action '{}' to group '{}'", actionId, groupId);
        }
    }

    /** Returns the group with this ID, or {@code null}. */
    public ActionGroup getGroup(String groupId) {
        return groups.get(groupId);
    }

    // ============== Menus and toolbars ==============

    /** Builds a menu bar with one top-level menu per group; IDs without a group are skipped. */
    public JMenuBar buildMenuBar(ActionContext context, String... topLevelGroupIds) {
        JMenuBar menuBar = new JMenuBar();
        for (String groupId : topLevelGroupIds) {
            ActionGroup group = groups.get(groupId);
            if (group != null) {
                menuBar.add(group.buildMenu(context));
            }
        }
        return menuBar;
    }

    /** Builds the menu of a group, or an empty menu named after the ID when there is no such group. */
    public JMenu buildMenu(ActionContext context, String groupId) {
        ActionGroup group = groups.get(groupId);
        return group != null ? group.buildMenu(context) : new JMenu(groupId);
    }

    /** Builds a toolbar from the given groups, flattening subgroups and adding a separator after each group. */
    public JToolBar buildToolBar(ActionContext context, String... groupIds) {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        for (String groupId : groupIds) {
            ActionGroup group = groups.get(groupId);
            if (group != null) {
                for (AnAction action : group.getActions()) {
                    javax.swing.Action swingAction = action.toSwingAction(context);
                    JButton button = toolBar.add(swingAction);
                    button.setVisible(!Boolean.FALSE.equals(swingAction.getValue(ActionPresentation.VISIBLE_KEY)));
                }
                toolBar.addSeparator();
            }
        }
        return toolBar;
    }

    // ============== Shortcuts ==============

    /** Runs the enabled action bound to the event's keystroke and returns whether one ran. */
    public boolean handleKeyEvent(KeyEvent e, ActionContext context) {
        KeyStroke stroke = KeyStroke.getKeyStrokeForEvent(e);
        String actionId = shortcutToActionId.get(stroke);
        if (actionId != null) {
            AnAction action = actions.get(actionId);
            if (action != null && action.isEnabled(context)) {
                action.actionPerformed(context);
                return true;
            }
        }
        return false;
    }

    // ============== Lifecycle ==============

    @Override
    public void dispose() {
        actions.clear();
        groups.clear();
        shortcutToActionId.clear();
    }
}
