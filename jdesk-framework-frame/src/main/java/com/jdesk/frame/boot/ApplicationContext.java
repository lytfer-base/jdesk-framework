package com.jdesk.frame.boot;

import com.jdesk.core.app.Application;
import com.jdesk.core.config.ComponentStore;
import com.jdesk.frame.action.ActionManager;
import com.jdesk.frame.icon.JdfIconManager;
import com.jdesk.frame.laf.JdfLafManager;
import com.jdesk.frame.settings.SettingsManager;
import com.jdesk.frame.swing.SwingUiDispatcher;
import com.jdesk.frame.ui.MainFrame;
import com.jdesk.frame.window.JdfWindowWatcher;

/**
 * Frame context handed to the desktop application: the {@link Application}, the main window and the common frame
 * services in one place, for startup code that sets up menus, workspace, settings, theme, icons and windows. It has
 * no lifecycle of its own; the core service container still owns the services.
 */
public final class ApplicationContext {

    private final Application application;
    private final MainFrame mainFrame;

    ApplicationContext(Application application, MainFrame mainFrame) {
        this.application = application;
        this.mainFrame = mainFrame;
    }

    public Application getApplication() {
        return application;
    }

    public MainFrame getMainFrame() {
        return mainFrame;
    }

    public ActionManager getActionManager() {
        return application.getService(ActionManager.class);
    }

    public SettingsManager getSettingsManager() {
        return application.getService(SettingsManager.class);
    }

    public JdfLafManager getLafManager() {
        return application.getService(JdfLafManager.class);
    }

    public JdfIconManager getIconManager() {
        return application.getService(JdfIconManager.class);
    }

    public SwingUiDispatcher getUiDispatcher() {
        return application.getService(SwingUiDispatcher.class);
    }

    public JdfWindowWatcher getWindowWatcher() {
        return application.getService(JdfWindowWatcher.class);
    }

    public ComponentStore getComponentStore() {
        return application.getService(ComponentStore.class);
    }

}
