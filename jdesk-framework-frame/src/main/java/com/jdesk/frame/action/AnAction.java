package com.jdesk.frame.action;

import javax.swing.*;

/**
 * Base class of user actions, shown as menu items, toolbar buttons or shortcut commands; modeled on IntelliJ
 * Platform's {@code AnAction}. An action keeps a stable ID and a template presentation, and each Swing control gets
 * its own copy of the {@link ActionPresentation}, so {@link #update(ActionContext, ActionPresentation)} can change
 * text, icon, enablement and visibility without touching the template.
 */
public abstract class AnAction {

    private final String id;

    private final ActionPresentation templatePresentation;

    protected AnAction(String id, String text) {
        this(id, text, null, null, null);
    }

    protected AnAction(String id, String text, String description, Icon icon, KeyStroke shortcut) {
        this.id = id;
        this.templatePresentation = new ActionPresentation(text, description, icon, shortcut);
    }

    /** Returns the ID the action is registered under. */
    public String getId() { return id; }

    /** Returns the text shown in menus, buttons and tooltips. */
    public String getText() { return templatePresentation.getText(); }

    /** Returns the longer description, usually shown as the tooltip. */
    public String getDescription() { return templatePresentation.getDescription(); }

    /** Returns the icon, or {@code null} if there is none. */
    public Icon getIcon() { return templatePresentation.getIcon(); }

    /** Returns the shortcut, or {@code null} if there is none. */
    public KeyStroke getShortcut() { return templatePresentation.getShortcut(); }

    public ActionPresentation getTemplatePresentation() {
        return templatePresentation;
    }

    /** Called when the action is triggered from a menu, a toolbar button or a shortcut. */
    public abstract void actionPerformed(ActionContext context);

    /** Returns whether the action is enabled; always true unless a subclass overrides it. */
    public boolean isEnabled(ActionContext context) {
        return true;
    }

    /**
     * Updates the presentation for the current context; subclasses may change its text, icon, shortcut, enablement
     * or visibility.
     */
    public void update(ActionContext context, ActionPresentation presentation) {
        presentation.setEnabled(isEnabled(context));
    }

    /**
     * Creates a {@link javax.swing.Action} for menus and toolbars that refreshes the presentation before every
     * enablement check and every run.
     */
    public javax.swing.Action toSwingAction(ActionContext context) {
        javax.swing.Action swingAction = new AbstractAction() {
            private final ActionPresentation presentation = templatePresentation.copy();

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                updatePresentation();
                if (!presentation.isEnabled() || !presentation.isVisible()) {
                    return;
                }
                AnAction.this.actionPerformed(context);
            }

            @Override
            public boolean isEnabled() {
                updatePresentation();
                return presentation.isEnabled() && presentation.isVisible();
            }

            private void updatePresentation() {
                presentation.setText(templatePresentation.getText());
                presentation.setDescription(templatePresentation.getDescription());
                presentation.setIcon(templatePresentation.getIcon());
                presentation.setShortcut(templatePresentation.getShortcut());
                presentation.setEnabled(true);
                presentation.setVisible(true);
                AnAction.this.update(context, presentation);
                putValue(NAME, presentation.getText());
                putValue(SMALL_ICON, presentation.getIcon());
                putValue(SHORT_DESCRIPTION, presentation.getDescription());
                putValue(ACCELERATOR_KEY, presentation.getShortcut());
                putValue(ActionPresentation.VISIBLE_KEY, presentation.isVisible());
            }
        };
        swingAction.isEnabled();
        return swingAction;
    }
}
