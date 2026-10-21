package com.jdesk.frame.action;

import com.jdesk.core.app.Application;

import javax.swing.*;
import java.awt.*;

/**
 * Context passed to a triggered action: the application, the component that triggered it and the parent window,
 * for reaching services and parenting dialogs.
 */
public class ActionContext {

    private final Application application;
    private final Component sourceComponent;
    private final JFrame parentFrame;

    public ActionContext(Application application, Component sourceComponent, JFrame parentFrame) {
        this.application = application;
        this.sourceComponent = sourceComponent;
        this.parentFrame = parentFrame;
    }

    public Application getApplication() { return application; }
    public Component getSourceComponent() { return sourceComponent; }
    public JFrame getParentFrame() { return parentFrame; }
}
