package com.jdesk.frame.ui;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationLifecycleListener;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.LocaleListener;
import com.jdesk.frame.action.ActionContext;
import com.jdesk.frame.icon.JdfIconManager;
import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.laf.JdfThemeListener;
import com.jdesk.frame.state.WindowStateService;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.components.JdfWorkspacePanel;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.window.JdfWindowWatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.Map;

/**
 * JDesk main window: a lighter take on IntelliJ's IdeFrameImpl with similar layers (window shell, custom root pane,
 * header, menu bar, toolbar, central workspace, tool window slots and status bar). It saves its bounds before
 * closing, takes part in application shutdown and refreshes its icon and colors when the theme changes.
 */
public class MainFrame extends JFrame {

    private static final Logger LOG = LoggerFactory.getLogger(MainFrame.class);
    private static final String WINDOW_STATE_KEY = "mainFrame";

    private final Application application;
    private final ActionContext actionContext;
    private final JdfRootPane frameRootPane;
    private final JdfHeader frameHeader;

    /** Startup code may replace or extend the menu bar and the toolbar. */
    private JMenuBar menuBar;
    private JToolBar toolBar;

    /** Central workspace; applications replace its content through {@link #setWorkspace(JComponent)}. */
    private JPanel contentPanel;
    private JPanel centerArea;
    private JdfStatusBar statusBar;

    private JPanel leftToolPanel;
    private JPanel rightToolPanel;
    private JPanel bottomToolPanel;
    /** Default titled borders of the tool window slots; adding a tool window replaces them with its title. */
    private final Map<JdfToolWindowArea, TitledBorder> placeholderBorders = new EnumMap<>(JdfToolWindowArea.class);
    private JLabel welcomeLabel;

    public MainFrame(Application application, String title) {
        super(title);
        this.application = application;
        this.actionContext = new ActionContext(application, this, this);
        this.frameRootPane = new JdfRootPane();
        this.frameHeader = new JdfHeader(application, this);
        this.frameHeader.setTitleText(title);

        installFrameShell();
        installApplicationIcon();
        // Background behind the opening animation; unset, it is white and flashes in dark themes.
        setBackground(JdfColors.background());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(JdfUi.size(1200, 800));
        setLocationRelativeTo(null);

        initComponents();
        layoutComponents();
        restoreWindowState();
        watchWindowFocus();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                storeWindowState();
                application.shutdown();
            }
        });

        application.getMessageBus().connect().subscribe(
                ApplicationLifecycleListener.class,
                new ApplicationLifecycleListener() {
                    @Override
                    public void appClosing(Application app) {
                        storeWindowState();
                    }

                    @Override
                    public void onApplicationShuttingDown(Application app) {
                        SwingUtilities.invokeLater(() -> dispose());
                    }
                });

        application.getMessageBus().connect().subscribe(
                JdfThemeListener.class,
                (oldTheme, newTheme) -> SwingUtilities.invokeLater(() -> {
                    installApplicationIcon();
                    applyThemeColors();
                    repaint();
                }));

        // Don't use invokeAndWait: I18n.switchTo still holds its class lock, so a switch on the EDT would deadlock.
        application.getMessageBus().connect().subscribe(
                LocaleListener.class,
                (oldLanguage, newLanguage) -> SwingUtilities.invokeLater(this::relabel));
    }

    /** Sets the slot titles, the welcome text and the status bar defaults in the current language. */
    private void relabel() {
        placeholderBorders.forEach((area, border) -> border.setTitle(placeholderTitle(area)));
        welcomeLabel.setText(I18n.message("frame.mainFrame.ready"));
        statusBar.relabel();
        revalidate();
        repaint();
    }

    private static String placeholderTitle(JdfToolWindowArea area) {
        return I18n.message(switch (area) {
            case LEFT -> "frame.mainFrame.leftTools";
            case RIGHT -> "frame.mainFrame.rightTools";
            case BOTTOM -> "frame.mainFrame.bottomTools";
        });
    }

    private void installFrameShell() {
        frameHeader.primeCustomTitleBar();
        frameRootPane.installHeader(frameHeader);
        setRootPane(frameRootPane);
    }

    private void installApplicationIcon() {
        try {
            application.getService(JdfIconManager.class).updateWindowIcon(this);
        }
        catch (IllegalStateException ignored) {
            LOG.debug("JdfIconManager is not registered; using default window icon");
        }
    }

    private void initComponents() {
        menuBar = new JMenuBar();

        toolBar = new JToolBar("Main Toolbar");
        toolBar.setFloatable(false);

        contentPanel = new JdfWorkspacePanel();

        leftToolPanel = createToolPanel(JdfToolWindowArea.LEFT);
        rightToolPanel = createToolPanel(JdfToolWindowArea.RIGHT);
        bottomToolPanel = createToolPanel(JdfToolWindowArea.BOTTOM);

        statusBar = new JdfStatusBar();
    }

    private JPanel createToolPanel(JdfToolWindowArea area) {
        JPanel panel = new JPanel(new BorderLayout());
        TitledBorder border = createToolPanelBorder(placeholderTitle(area));
        placeholderBorders.put(area, border);
        panel.setBorder(border);
        panel.setMinimumSize(new Dimension(0, 0));
        return panel;
    }

    private void layoutComponents() {
        setJMenuBar(menuBar);
        frameRootPane.setToolBar(toolBar);

        Container cp = getContentPane();
        cp.setLayout(new BorderLayout());

        centerArea = new JPanel(new BorderLayout());
        centerArea.add(leftToolPanel, BorderLayout.WEST);
        centerArea.add(rightToolPanel, BorderLayout.EAST);
        centerArea.add(contentPanel, BorderLayout.CENTER);
        centerArea.add(bottomToolPanel, BorderLayout.SOUTH);
        cp.add(centerArea, BorderLayout.CENTER);
        cp.add(statusBar, BorderLayout.SOUTH);

        welcomeLabel = new JLabel(I18n.message("frame.mainFrame.ready"), SwingConstants.CENTER);
        welcomeLabel.setFont(JdfFonts.derive(welcomeLabel.getFont(), Font.BOLD, 18f));
        contentPanel.add(welcomeLabel, BorderLayout.CENTER);

        applyThemeColors();
    }

    private void applyThemeColors() {
        Container contentPane = getContentPane();
        if (contentPane != null) {
            contentPane.setBackground(JdfColors.background());
        }
        if (centerArea != null) {
            centerArea.setBackground(JdfColors.background());
        }
        if (contentPanel != null) {
            contentPanel.setBackground(JdfColors.background());
            updateTextColors(contentPanel);
        }
        if (menuBar != null) {
            styleMenuBar(menuBar);
        }
        if (toolBar != null) {
            styleToolBar(toolBar);
        }
        if (statusBar != null) {
            statusBar.updateTheme();
        }
        if (frameHeader != null) {
            frameHeader.updateTheme();
        }
        styleToolPanel(leftToolPanel);
        styleToolPanel(rightToolPanel);
        styleToolPanel(bottomToolPanel);
        frameRootPane.updateTheme();
    }

    private void styleMenuBar(JMenuBar bar) {
        bar.setOpaque(true);
        bar.setBackground(JdfColors.panelBackground());
        bar.setBorder(JdfUi.bottomBorder());
        for (Component component : bar.getComponents()) {
            component.setBackground(JdfColors.panelBackground());
            component.setForeground(JdfColors.text());
            if (component instanceof JMenu menu) {
                menu.setOpaque(false);
            }
        }
    }

    private void styleToolBar(JToolBar bar) {
        bar.setOpaque(true);
        bar.setBackground(JdfColors.panelBackground());
        bar.setBorder(JdfUi.bottomBorder());
        updateTextColors(bar);
    }

    private void styleToolPanel(JPanel panel) {
        if (panel == null) {
            return;
        }
        panel.setOpaque(true);
        panel.setBackground(JdfColors.panelBackground());
        if (panel.getBorder() instanceof TitledBorder titledBorder) {
            titledBorder.setTitleColor(JdfColors.mutedText());
            titledBorder.setBorder(JdfUi.lineBorder(JdfColors.border()));
        }
        updateTextColors(panel);
    }

    private TitledBorder createToolPanelBorder(String title) {
        TitledBorder border = BorderFactory.createTitledBorder(
                JdfUi.lineBorder(JdfColors.border()), title);
        border.setTitleColor(JdfColors.mutedText());
        return border;
    }

    private void updateTextColors(Container container) {
        JdfUi.updateTreeTheme(container);
    }

    private void restoreWindowState() {
        try {
            application.getService(WindowStateService.class).restoreFrameState(WINDOW_STATE_KEY, this);
        }
        catch (IllegalStateException ignored) {
            LOG.debug("WindowStateService is not registered; using default main frame bounds");
        }
    }

    private void storeWindowState() {
        try {
            application.getService(WindowStateService.class).storeFrameState(WINDOW_STATE_KEY, this);
        }
        catch (IllegalStateException ignored) {
            LOG.debug("WindowStateService is not registered; main frame bounds will not be persisted");
        }
    }

    private void watchWindowFocus() {
        try {
            application.getService(JdfWindowWatcher.class).watch(this);
        }
        catch (IllegalStateException ignored) {
            LOG.debug("JdfWindowWatcher is not registered; parent window tracking is disabled");
        }
    }

    public Application getApplication() {
        return application;
    }

    public ActionContext getActionContext() {
        return actionContext;
    }

    @Override
    public void setTitle(String title) {
        super.setTitle(title);
        if (frameHeader != null) {
            frameHeader.setTitleText(title);
        }
    }

    @Override
    public void setJMenuBar(JMenuBar menuBar) {
        this.menuBar = menuBar;
        super.setJMenuBar(menuBar);
        if (menuBar != null) {
            styleMenuBar(menuBar);
        }
        if (frameRootPane != null) {
            frameRootPane.revalidate();
            frameRootPane.repaint();
        }
    }

    @Override
    public JMenuBar getJMenuBar() {
        return menuBar != null ? menuBar : super.getJMenuBar();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // Handle created, not yet shown: set the brush and dark mode now, or the opening animation flashes light.
        NativeWindowBackground.apply(this, JdfColors.background());
        NativeTitleBar.setDarkMode(this, JdfColors.isDark());
    }

    public JdfRootPane getJdfRootPane() {
        return frameRootPane;
    }

    public JdfHeader getJdfHeader() {
        return frameHeader;
    }

    public JdfStatusBar getJdfStatusBar() {
        return statusBar;
    }

    public JToolBar getToolBar() {
        return toolBar;
    }

    public JPanel getContentPanel() {
        return contentPanel;
    }

    public JPanel getWorkspacePanel() {
        return contentPanel;
    }

    /** Replaces the content of the central workspace; {@code null} clears it. */
    public void setWorkspace(JComponent workspace) {
        contentPanel.removeAll();
        if (workspace != null) {
            contentPanel.add(workspace, BorderLayout.CENTER);
        }
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    /** Adds a component, such as a search field, to the north extension area, replacing any with the same key. */
    public void addNorthExtension(String key, JComponent component) {
        frameRootPane.addNorthExtension(key, component);
    }

    /** Removes and returns the north extension component with this key. */
    public JComponent removeNorthExtension(String key) {
        return frameRootPane.removeNorthExtension(key);
    }

    /** Returns the panel of a tool window area. */
    public JPanel getToolWindowPanel(JdfToolWindowArea area) {
        return switch (area) {
            case LEFT -> leftToolPanel;
            case RIGHT -> rightToolPanel;
            case BOTTOM -> bottomToolPanel;
        };
    }

    /** Sets the content of a tool window area and shows it; {@code null} clears and hides the area. */
    public void setToolWindowComponent(JdfToolWindowArea area, JComponent component) {
        JPanel panel = getToolWindowPanel(area);
        panel.removeAll();
        if (component != null) {
            panel.add(component, BorderLayout.CENTER);
            panel.setVisible(true);
        }
        else {
            panel.setVisible(false);
        }
        panel.revalidate();
        panel.repaint();
        revalidate();
        repaint();
    }

    /** Clears and hides a tool window area. */
    public void clearToolWindowArea(JdfToolWindowArea area) {
        setToolWindowComponent(area, null);
    }

    /** Shows or hides the main toolbar. */
    public void setToolBarVisible(boolean visible) {
        toolBar.setVisible(visible);
        revalidate();
        repaint();
    }

    /** Shows or hides all tool window areas. */
    public void setToolWindowsVisible(boolean visible) {
        leftToolPanel.setVisible(visible);
        rightToolPanel.setVisible(visible);
        bottomToolPanel.setVisible(visible);
        revalidate();
        repaint();
    }

    /** Shows or hides the status bar. */
    public void setStatusBarVisible(boolean visible) {
        statusBar.setVisible(visible);
        revalidate();
        repaint();
    }

    /** Sets the main status bar text. */
    public void setStatusText(String text) {
        statusBar.setInfo(text);
    }

    /** Shows a lightweight notification by putting the message in the status bar and the log. */
    public void showNotification(String message) {
        setStatusText(message);
        LOG.info("Notification: {}", message);
    }

    /** Adds a titled component to the left tool window area. */
    public void addLeftToolWindow(String title, JComponent component) {
        addToolPanel(leftToolPanel, title, component);
    }

    /** Adds a titled component to the right tool window area. */
    public void addRightToolWindow(String title, JComponent component) {
        addToolPanel(rightToolPanel, title, component);
    }

    /** Adds a titled component to the bottom tool window area. */
    public void addBottomToolWindow(String title, JComponent component) {
        addToolPanel(bottomToolPanel, title, component);
    }

    /** Appends a component to the header's action area. */
    public void addHeaderAction(JComponent component) {
        frameHeader.addHeaderAction(component);
    }

    private void addToolPanel(JPanel container, String title, JComponent component) {
        if (container.getComponentCount() == 0) {
            container.removeAll();
            container.setBorder(createToolPanelBorder(title));
            container.add(component, BorderLayout.CENTER);
        }
        else {
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setBorder(createToolPanelBorder(title));
            wrapper.add(new JScrollPane(component), BorderLayout.CENTER);
            wrapper.setPreferredSize(JdfUi.size(250, 300));
            container.setLayout(new javax.swing.BoxLayout(container, javax.swing.BoxLayout.Y_AXIS));
            container.add(wrapper);
        }
        styleToolPanel(container);
        container.revalidate();
        container.repaint();
    }
}
