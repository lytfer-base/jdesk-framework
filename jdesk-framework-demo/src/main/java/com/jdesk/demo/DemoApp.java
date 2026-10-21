package com.jdesk.demo;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationInfo;
import com.jdesk.core.app.ApplicationLifecycleListener;
import com.jdesk.core.app.PathManager;
import com.jdesk.concurrency.AppLock;
import com.jdesk.core.config.ComponentStore;
import com.jdesk.core.i18n.I18nStartup;
import com.jdesk.demo.settings.*;
import com.jdesk.frame.action.ActionContext;
import com.jdesk.frame.action.ActionManager;
import com.jdesk.frame.action.AnAction;
import com.jdesk.frame.boot.JdeskApplicationBuilder;
import com.jdesk.frame.boot.ApplicationContext;
import com.jdesk.frame.icon.JdfIconManager;
import com.jdesk.frame.laf.JdfLafManager;
import com.jdesk.frame.settings.SettingChoice;
import com.jdesk.frame.settings.SettingDescriptor;
import com.jdesk.frame.settings.SettingType;
import com.jdesk.frame.settings.SettingsManager;
import com.jdesk.frame.ui.DialogBuilder;
import com.jdesk.frame.ui.MainFrame;
import com.jdesk.frame.ui.Notification;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.core.plugins.PluginClassLoader;
import com.jdesk.core.plugins.PluginDescriptor;
import com.jdesk.core.plugins.PluginManager;
import com.jdesk.demo.runtime.params.DemoRuntimeParams;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;
import com.jdesk.util.Platform;
import com.jdesk.util.SystemInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo application that exercises the framework: actions and action groups, notifications, settings, themes, plugins,
 * the VFS, the message bus, AppLock and the WebView. See the module README for the menu entries that drive each part.
 */
public class DemoApp {

    private static final Logger LOG = LoggerFactory.getLogger(DemoApp.class);
    private static final String DEMO_GREETER_PLUGIN_ID = "com.jdesk.demo.greeter";
    private static final String PLUGIN_GREETER_SERVICE = "com.jdesk.demo.plugin.PluginGreeterService";

    private AppLock appLock;
    private AtomicInteger writeCounter = new AtomicInteger();

    public static void main(String[] args) {
        // Demo text is English only: negotiate as if the system were English unless Settings picks a language.
        if (System.getProperty(I18nStartup.OS_LANGUAGES_PROPERTY) == null) {
            System.setProperty(I18nStartup.OS_LANGUAGES_PROPERTY, "en");
        }
        new DemoApp().run();
    }

    private void run() {
        JdeskApplicationBuilder.create()
                .applicationClassLoader(DemoApp.class.getClassLoader())
                .applicationActivity(this::setupApplication)
                .postStartupActivity(this::afterApplicationStarted)
                .launchBlocking();
    }

    private void setupApplication(ApplicationContext frameContext) {
        Application app = frameContext.getApplication();
        LOG.info("Dev mode: {}, home: {}", PathManager.isDevelopmentMode(), PathManager.getHomePath());

        appLock = new AppLock();

        // ----- Services -----
        app.getServiceContainer().registerInstance(DemoService.class, new DemoService());
        ComponentStore componentStore = frameContext.getComponentStore();
        componentStore.register(new DemoConfig());

        ActionManager am = frameContext.getActionManager();
        registerActions(am, frameContext.getIconManager());
        registerSettings(frameContext.getSettingsManager());

        app.getMessageBus().connect().subscribe(ApplicationLifecycleListener.class,
                new ApplicationLifecycleListener() {
                    @Override
                    public void onApplicationStarted(Application a) {
                        LOG.info("Application started — all {} actions registered", am.getAllActions().size());
                    }

                });

        MainFrame frame = frameContext.getMainFrame();
        ActionContext ctx = frame.getActionContext();

        frame.setJMenuBar(am.buildMenuBar(ctx, "MainMenu.File", "MainMenu.Edit", "MainMenu.Tools", "MainMenu.Help"));

        JToolBar tb = frame.getToolBar();
        tb.removeAll();
        for (AnAction a : am.getGroup("MainToolbar").getActions()) {
            tb.add(a.toSwingAction(ctx));
        }
        frame.addHeaderAction(createHeaderActionButton(am.getAction("tools.theme.toggle"), ctx));
    }

    private JButton createHeaderActionButton(AnAction action, ActionContext ctx) {
        JButton button = new JButton(action.toSwingAction(ctx));
        button.setFocusable(false);
        button.setContentAreaFilled(false);
        button.setBorder(JdfUi.empty(4, 8, 4, 8));
        return button;
    }

    private void afterApplicationStarted(ApplicationContext frameContext) {
        Application app = frameContext.getApplication();
        ActionManager am = frameContext.getActionManager();
        MainFrame frame = frameContext.getMainFrame();

        PluginManager pm = app.getService(PluginManager.class);
        if (pm != null) {
            LOG.info("Plugin Manager: {} plugins found, {} started",
                    pm.getAllPlugins().size(), pm.getStartedPlugins().size());
            for (PluginDescriptor pd : pm.getAllPlugins()) {
                LOG.info("  Plugin: {} v{} [{}]", pd.getPluginId(), pd.getVersion(), pd.getName());
            }
        }

        buildToolWindows(frame);
        frame.showNotification(am.getAllActions().size() + " actions loaded");

        Notification.info("JDesk Framework Demo",
                        "Actions, settings, themes, plugins, VFS and WebView<br/>" +
                                SystemInfo.formatMemory(SystemInfo.TOTAL_MEMORY) + " available")
                .withTimeout(6000)
                .show(frame);
    }

    // ============== Actions ==============

    private void registerActions(ActionManager am, JdfIconManager icons) {
        Icon informationIcon = icons.getIcon("icons/idea/information.svg");
        Icon settingsIcon = icons.getIcon("icons/idea/settings.svg");
        Icon notificationsIcon = icons.getIcon("icons/idea/notifications.svg");
        Icon pluginIcon = icons.getIcon("icons/idea/plugin.svg");
        Icon runIcon = icons.getIcon("icons/idea/run.svg");

        // File menu actions
        am.registerAction(new AnAction("file.about", "About",
                "Show product and system information", informationIcon, null) {
            @Override
            public void actionPerformed(ActionContext ctx) {
                showSystemInfo(ctx);
            }
        });
        am.registerAction(new AnAction("file.config", "Configuration...") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                showConfig(ctx);
            }
        });
        am.registerAction(new AnAction("file.settings", "Settings...",
                "Open framework settings", settingsIcon, null) {
            @Override
            public void actionPerformed(ActionContext ctx) {
                showSettings(ctx);
            }
        });
        am.registerAction(new AnAction("file.exit", "Exit") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                ctx.getApplication().shutdown();
            }
        });

        // Edit menu actions
        am.registerAction(new AnAction("edit.notify.info", "Show Info Notification",
                "Show an information notification", notificationsIcon, null) {
            @Override
            public void actionPerformed(ActionContext ctx) {
                Notification.info("Information", "This is an info notification from the ActionManager.")
                        .withAction("Details", () -> LOG.info("Info action clicked"))
                        .show(ctx.getParentFrame());
            }
        });
        am.registerAction(new AnAction("edit.notify.warn", "Show Warning Notification") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                Notification.warning("Warning", "Disk space is running low on " + SystemInfo.OS_NAME + ".")
                        .show(ctx.getParentFrame());
            }
        });
        am.registerAction(new AnAction("edit.notify.error", "Show Error Notification") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                Notification.error("Error", "An unexpected error occurred while processing.")
                        .show(ctx.getParentFrame());
            }
        });

        // Tools menu actions
        am.registerAction(new AnAction("tools.greetings", "Show Greetings...") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                showGreetings(ctx);
            }
        });
        am.registerAction(new AnAction("tools.di", "Test DI Container...") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                testDi(ctx);
            }
        });
        am.registerAction(new AnAction("tools.lock", "Test AppLock...") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                testAppLock(ctx);
            }
        });
        am.registerAction(new AnAction("tools.msg", "Test Message Bus...") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                testMessageBus(ctx);
            }
        });
        am.registerAction(new AnAction("tools.webview", "Test WebView...") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                showWebViewDemo(ctx);
            }
        });
        am.registerAction(new AnAction("tools.webview.level1", "WebView Level 1 Compatibility...") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                showWebViewLevel1Compatibility(ctx);
            }
        });
        am.registerAction(new AnAction("tools.webview.benchmark", "WebView Startup Benchmark...") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                showWebViewStartupBenchmark(ctx);
            }
        });
        am.registerAction(new AnAction("tools.theme.toggle", "Toggle Theme",
                "Switch between light and dark themes", settingsIcon, null) {
            @Override
            public void actionPerformed(ActionContext ctx) {
                toggleTheme(ctx);
            }
        });
        am.registerAction(new AnAction("tools.browse", "Browse File System...") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                showFileBrowser(ctx);
            }
        });
        am.registerAction(new AnAction("tools.plugins", "Plugin Manager...",
                "Show loaded plugins", pluginIcon, null) {
            @Override
            public void actionPerformed(ActionContext ctx) {
                showPlugins(ctx);
            }
        });
        am.registerAction(new AnAction("tools.lazysvc", "Test Lazy Plugin Service...") {
            @Override
            public void actionPerformed(ActionContext ctx) {
                testLazyPluginService(ctx);
            }
        });

        // Toolbar actions
        am.registerAction(new AnAction("tb.greet", "Greetings",
                "Show extension greetings", runIcon, null) {
            @Override
            public void actionPerformed(ActionContext ctx) {
                showGreetings(ctx);
            }
        });
        am.registerAction(new AnAction("tb.lock", "AppLock",
                "Run AppLock validation", settingsIcon, null) {
            @Override
            public void actionPerformed(ActionContext ctx) {
                testAppLock(ctx);
            }
        });
        am.registerAction(new AnAction("tb.notify", "Notify",
                "Show a quick notification", notificationsIcon, null) {
            @Override
            public void actionPerformed(ActionContext ctx) {
                Notification.info("Quick Notification", "Triggered at " + java.time.LocalTime.now())
                        .withTimeout(3000)
                        .show(ctx.getParentFrame());
            }
        });

        // ----- Define groups -----
        am.registerGroup("MainMenu.File", "File")
                .addAction(am.getAction("file.settings"))
                .addAction(am.getAction("file.config"))
                .addSeparator()
                .addAction(am.getAction("file.exit"));

        am.registerGroup("MainMenu.Edit", "Edit")
                .addAction(am.getAction("edit.notify.info"))
                .addAction(am.getAction("edit.notify.warn"))
                .addAction(am.getAction("edit.notify.error"));

        am.registerGroup("MainMenu.Tools", "Tools")
                .addAction(am.getAction("tools.greetings"))
                .addAction(am.getAction("tools.di"))
                .addAction(am.getAction("tools.lock"))
                .addAction(am.getAction("tools.msg"))
                .addAction(am.getAction("tools.webview"))
                .addAction(am.getAction("tools.webview.level1"))
                .addAction(am.getAction("tools.webview.benchmark"))
                .addAction(am.getAction("tools.theme.toggle"))
                .addAction(am.getAction("tools.plugins"))
                .addAction(am.getAction("tools.lazysvc"))
                .addSeparator()
                .addAction(am.getAction("tools.browse"));

        am.registerGroup("MainMenu.Help", "Help")
                .addAction(am.getAction("file.about"));

        am.registerGroup("MainToolbar", "Main Toolbar")
                .addAction(am.getAction("tb.greet"))
                .addAction(am.getAction("tb.lock"))
                .addAction(am.getAction("tb.notify"));
    }

    // ============== Tool Windows ==============

    private void buildToolWindows(MainFrame frame) {
        Application app = frame.getApplication();

        // Right: Framework status
        app.getExtensionRegistry().registerExtension("deskframe.demo.toolWindow",
                (ToolWindowProvider) () -> {
                    JTextArea area = new JTextArea(12, 28);
                    area.setEditable(false);
                    area.setFont(JdfFonts.mono());
                    area.setText("""
                            JDesk Framework Demo
                            ════════════════════

                            Modules:
                             - jdesk-framework-core
                               Application, services,
                               extensions, plugins,
                               MessageBus, ComponentStore,
                               VFS, i18n, AppLock,
                               EdtScheduler, Pair, Triple,
                               StringUtil, SystemInfo,
                               XmlHelper
                             - jdesk-framework-frame
                               actions, settings, themes,
                               notifications, dialogs,
                               WebView (JCEF), SQLite,
                               VFS, local history,
                               ACP, MCP

                            """);
                    return new JScrollPane(area);
                });
        app.getExtensionRegistry().forEachExtensionSafe("deskframe.demo.toolWindow",
                ToolWindowProvider.class,
                p -> frame.addRightToolWindow("Modules", p.createComponent()));

        // Left: Greetings
        DefaultListModel<String> listModel = new DefaultListModel<>();
        app.getExtensionRegistry().forEachExtensionSafe("deskframe.demo.greeting",
                GreetingProvider.class,
                g -> listModel.addElement(g.getGreeting()));
        frame.addLeftToolWindow("Greetings", new JScrollPane(new JList<>(listModel)));

        // Bottom: System info
        JTextArea sysArea = new JTextArea(4, 50);
        sysArea.setEditable(false);
        sysArea.setFont(JdfFonts.mono());
        sysArea.setText(SystemInfo.getSummary());
        frame.addBottomToolWindow("System", new JScrollPane(sysArea));

        // Left: File browser
        JTree fileTree = FileBrowserPanel.createFileBrowserTree(app);
        frame.addLeftToolWindow("Files", new JScrollPane(fileTree));
    }

    // ============== Action Handlers ==============

    private void showSystemInfo(ActionContext ctx) {
        ApplicationInfo info = ctx.getApplication().getService(ApplicationInfo.class);
        DialogBuilder.create("About JDesk Framework")
                .withMessage(String.format("""
                                <h2>%s</h2>
                                <p>%s — %s</p>
                                <p><i>Platform (via Kotlin): <b>%s</b></i></p>
                                <hr/>
                                <h3>System</h3>
                                <pre>%s</pre>
                                <h3>Modules</h3>
                                <ul>
                                  <li><b>jdesk-framework-core</b> — Application, services, extensions, plugins, MessageBus, ComponentStore, VFS, i18n, concurrency (AppLock, EdtScheduler) and util (Pair, Triple, StringUtil, SystemInfo, XmlHelper)</li>
                                  <li><b>jdesk-framework-frame</b> — actions, settings, themes, notifications, dialogs, WebView (JCEF), SQLite storage, VFS and local history, ACP and MCP</li>
                                  <li><b>jdesk-framework-demo</b> — this application</li>
                                </ul>
                                """, info.getFullProductName(), info.getDisplayVersion(), info.getCompanyName(),
                        Platform.INSTANCE.getName(), SystemInfo.getSummary().replace("\n", "<br/>")))
                .withOkButton()
                .withPreferredSize(480, 600)
                .showAndGet(ctx.getParentFrame());
    }

    private void showConfig(ActionContext ctx) {
        ComponentStore store = ctx.getApplication().getService(ComponentStore.class);
        DialogBuilder.create("Configuration")
                .withMessage("<h3>Component Store</h3><p><code>" + store.getConfigDir().toAbsolutePath() + "</code></p>")
                .withOkButton()
                .showAndGet(ctx.getParentFrame());
    }

    private void showGreetings(ActionContext ctx) {
        Application app = ctx.getApplication();
        StringBuilder sb = new StringBuilder("<h3>Extension Greetings</h3><ul>");
        app.getExtensionRegistry().forEachExtensionSafe("deskframe.demo.greeting",
                GreetingProvider.class,
                g -> sb.append("<li>").append(g.getGreeting()).append("</li>"));
        sb.append("</ul>");
        DialogBuilder.create("Greetings").withMessage(sb.toString()).withOkButton().showAndGet(ctx.getParentFrame());
    }

    private void testDi(ActionContext ctx) {
        Application app = ctx.getApplication();
        DemoService svc = app.getService(DemoService.class);
        ActionManager am = app.getService(ActionManager.class);
        String report = """
                <h3>DI Container</h3>
                <table>
                <tr><td>DemoService:</td><td><b>✓ %s</b></td></tr>
                <tr><td>ActionManager:</td><td><b>✓ %s (%d actions)</b></td></tr>
                <tr><td>ComponentStore:</td><td><b>✓ %s</b></td></tr>
                </table>
                """.formatted(svc.getClass().getSimpleName(),
                am.getClass().getSimpleName(), am.getAllActions().size(),
                app.getService(ComponentStore.class).getClass().getSimpleName());
        DialogBuilder.create("DI Test").withMessage(report).withOkButton().showAndGet(ctx.getParentFrame());
    }

    private void testAppLock(ActionContext ctx) {
        StringBuilder log = new StringBuilder("<h3>AppLock Test</h3><ol>");

        // Read action
        appLock.read(() -> {
            log.append("<li>✓ Read lock acquired (count: ")
                    .append(writeCounter.get()).append(")</li>");
        });
        log.append("<li>Read completed</li>");

        // Write action
        appLock.write(() -> {
            int val = writeCounter.incrementAndGet();
            log.append("<li>✓ Write lock acquired, counter = ").append(val).append("</li>");
        });
        log.append("<li>Write completed</li>");

        // Parallel reads
        appLock.read(() -> {
            log.append("<li>✓ Concurrent read 1</li>");
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        });
        log.append("<li>Read 1 done</li>");

        log.append("</ol><p>Counter final: <b>").append(writeCounter.get()).append("</b></p>");

        DialogBuilder.create("AppLock Test").withMessage(log.toString()).withOkButton().showAndGet(ctx.getParentFrame());
    }

    private void testMessageBus(ActionContext ctx) {
        Application app = ctx.getApplication();
        StringBuilder log = new StringBuilder("<h3>Message Bus Test</h3><ol>");

        var conn = app.getMessageBus().connect();
        conn.subscribe(ApplicationLifecycleListener.class, new ApplicationLifecycleListener() {
            @Override
            public void onApplicationStarted(Application a) {
                log.append("<li>✓ Subscriber received event!</li>");
            }
        });
        log.append("<li>Subscribed</li>");

        app.getMessageBus().syncPublisher(ApplicationLifecycleListener.class)
                .onApplicationStarted(app);
        log.append("<li>Published via syncPublisher</li>");

        log.append("</ol>");
        conn.dispose();

        DialogBuilder.create("Message Bus").withMessage(log.toString()).withOkButton().showAndGet(ctx.getParentFrame());
    }

    private void toggleTheme(ActionContext ctx) {
        JdfLafManager lafManager = ctx.getApplication().getService(JdfLafManager.class);
        String nextThemeId = JdfLafManager.DARK.equals(lafManager.getCurrentThemeId())
                ? JdfLafManager.LIGHT
                : JdfLafManager.DARK;
        lafManager.setCurrentTheme(nextThemeId);
        Notification.info("Theme", "Switched to " + lafManager.getCurrentTheme().getDisplayName())
                .withTimeout(2500)
                .show(ctx.getParentFrame());
    }

    private void showWebViewDemo(ActionContext ctx) {
        DialogBuilder.create("WebView Demo")
                .withCenterComponent(WebViewDemoPanel.create(ctx.getApplication()))
                .withOkButton()
                .withPreferredSize(900, 640)
                .showAndGet(ctx.getParentFrame());
    }

    private void showWebViewLevel1Compatibility(ActionContext ctx) {
        DialogBuilder.create("WebView Level 1 Compatibility")
                .withCenterComponent(WebViewLevel1CompatibilityPanel.create(ctx.getApplication()))
                .withOkButton()
                .withPreferredSize(1080, 760)
                .showAndGet(ctx.getParentFrame());
    }

    private void showWebViewStartupBenchmark(ActionContext ctx) {
        DialogBuilder.create("WebView Startup Benchmark")
                .withCenterComponent(WebViewStartupBenchmarkPanel.create(ctx.getApplication()))
                .withOkButton()
                .withPreferredSize(980, 720)
                .showAndGet(ctx.getParentFrame());
    }

    private void showPlugins(ActionContext ctx) {
        PluginManager pm = ctx.getApplication().getService(PluginManager.class);
        if (pm == null) {
            Notification.warning("Plugin Manager", "Plugin Manager is not available.")
                    .show(ctx.getParentFrame());
            return;
        }

        StringBuilder sb = new StringBuilder("<h2>Plugin Manager</h2>");
        sb.append("<table border='1' cellpadding='4' cellspacing='0'>");
        sb.append("<tr><th>ID</th><th>Name</th><th>Version</th><th>State</th><th>Extensions</th></tr>");

        for (PluginDescriptor pd : pm.getAllPlugins()) {
            String state = pm.getPluginState(pd.getPluginId()).name();
            String color = state.equals("STARTED") ? "green" : "gray";
            sb.append("<tr>");
            sb.append("<td><code>").append(pd.getPluginId()).append("</code></td>");
            sb.append("<td>").append(pd.getName()).append("</td>");
            sb.append("<td>").append(pd.getVersion()).append("</td>");
            sb.append("<td style='color:").append(color).append("'><b>")
                    .append(state).append("</b></td>");
            sb.append("<td>").append(pd.getExtensions().size()).append("</td>");
            sb.append("</tr>");
        }

        sb.append("</table>");

        if (pm.getAllPlugins().isEmpty()) {
            sb.append("<p><i>No plugins found. Place plugins in the directory below.</i></p>");
        }

        sb.append("<hr/><p><b>Plugins directory:</b> <code>")
                .append(PathManager.getPluginsDir().toAbsolutePath()).append("</code></p>");

        DialogBuilder.create("Plugin Manager")
                .withMessage(sb.toString())
                .withOkButton()
                .withPreferredSize(580, 350)
                .showAndGet(ctx.getParentFrame());
    }

    private void testLazyPluginService(ActionContext ctx) {
        Application app = ctx.getApplication();
        PluginManager pm = app.getService(PluginManager.class);
        PluginClassLoader pluginClassLoader = pm != null ? pm.getPluginClassLoader(DEMO_GREETER_PLUGIN_ID) : null;
        if (pluginClassLoader == null) {
            Notification.warning("Lazy Plugin Service",
                            "Demo Greeter plugin is not loaded. Build demo-greeter.jar and place it in "
                                    + PathManager.getPluginsDir().toAbsolutePath())
                    .show(ctx.getParentFrame());
            return;
        }

        try {
            Class<?> serviceClass = pluginClassLoader.loadClass(PLUGIN_GREETER_SERVICE);

            // Check if the service is registered but not yet created
            Object beforeSvc = getPluginServiceIfCreated(app, serviceClass);

            long t0 = System.currentTimeMillis();
            // This is the FIRST access — triggers lazy class loading and instantiation
            Object svc = getPluginService(app, serviceClass);
            long t1 = System.currentTimeMillis();

            String greeting = (String) serviceClass.getMethod("greet", String.class).invoke(svc, "World");
            String pluginInfo = (String) serviceClass.getMethod("getPluginInfo").invoke(svc);

            String report = "<h2>Lazy Plugin Service Test</h2>"
                    + "<table>"
                    + "<tr><td>Service interface:</td><td><b>" + serviceClass.getName() + "</b></td></tr>"
                    + "<tr><td>Class loader:</td><td><b>" + serviceClass.getClassLoader().getClass().getSimpleName() + "</b></td></tr>"
                    + "<tr><td>Before getService():</td><td><b>" + (beforeSvc == null ? "null (not created)" : "EXISTS") + "</b></td></tr>"
                    + "<tr><td>After getService():</td><td><b>" + svc.getClass().getName() + "</b></td></tr>"
                    + "<tr><td>Load time:</td><td><b>" + (t1 - t0) + " ms</b></td></tr>"
                    + "<tr><td>greet(\"World\"):</td><td><b>" + greeting + "</b></td></tr>"
                    + "<tr><td>getPluginInfo():</td><td><b>" + pluginInfo + "</b></td></tr>"
                    + "</table>"
                    + "<hr/>"
                    + "<p><i>The PluginGreeterServiceImpl class was NOT loaded at startup — "
                    + "it was loaded and instantiated only when getService() was called for the first time.</i></p>"
                    + "<p><i>Try checking the log output for: 'PluginGreeterServiceImpl instantiated (lazy load at ...)'</i></p>";

            DialogBuilder.create("Lazy Plugin Service")
                    .withMessage(report)
                    .withOkButton()
                    .withPreferredSize(620, 360)
                    .showAndGet(ctx.getParentFrame());
        } catch (Exception e) {
            LOG.error("Failed to use lazy plugin service", e);
            Notification.error("Lazy Plugin Service", "Failed to load plugin service: " + e.getMessage())
                    .show(ctx.getParentFrame());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object getPluginService(Application app, Class<?> serviceClass) {
        return app.getService((Class) serviceClass);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object getPluginServiceIfCreated(Application app, Class<?> serviceClass) {
        return app.getServiceContainer().getServiceIfCreated((Class) serviceClass);
    }

    private void showSettings(ActionContext ctx) {
        ctx.getApplication().getService(SettingsManager.class).showSettings(ctx.getParentFrame());
    }

    private void registerSettings(SettingsManager settingsManager) {
        settingsManager.registerConfigurable("general", "General", new GeneralSettingsConfigurable());
        settingsManager.registerConfigurable("appearance", "Appearance", new FontConfigurable());
        settingsManager.registerConfigurable("editor", "Editor", new EditorTabsConfigurable());
        settingsManager.registerConfigurable("editor", "Editor", new EditorSpacingConfigurable());
        settingsManager.registerSettingsPage("runtime", "Runtime", "Runtime Parameters", List.of(
                SettingDescriptor.integerSetting(
                                DemoRuntimeParams.TOOLBAR_UI_DEFAULT_HEIGHT_KEY, "Toolbar height", 36)
                        .description("Default height used by toolbar-like surfaces.")
                        .group("runtime", "Runtime")
                        .range(28, 56, 1)
                        .keywords("toolbar", "height", "ui", "runtime")
                        .runtimeBinding(DemoRuntimeParams.TOOLBAR_UI_DEFAULT_HEIGHT)
                        .build(),
                SettingDescriptor.booleanSetting(
                                FrameRuntimeParams.FRAME_UI_COMPACT_MODE_KEY, "Compact mode", false)
                        .description("Use denser spacing for frame-level UI surfaces.")
                        .group("runtime", "Runtime")
                        .keywords("compact", "density", "spacing", "ui")
                        .runtimeBinding(FrameRuntimeParams.FRAME_UI_COMPACT_MODE)
                        .build(),
                SettingDescriptor.builder(FrameRuntimeParams.FRAME_UI_THEME_KEY, SettingType.CHOICE)
                        .displayName("Theme preference")
                        .description("Preferred UI theme key exposed to runtime code.")
                        .group("runtime", "Runtime")
                        .defaultValue("light")
                        .choices(
                                SettingChoice.of("light", "Light"),
                                SettingChoice.of("dark", "Dark"),
                                SettingChoice.of("system", "System"))
                        .keywords("theme", "laf", "look", "feel", "appearance")
                        .runtimeBinding(FrameRuntimeParams.FRAME_UI_THEME)
                        .build()
        ));
    }

    // ============== VFS / File Browser ==============

    private void showFileBrowser(ActionContext ctx) {
        Application app = ctx.getApplication();
        JTree tree = FileBrowserPanel.createFileBrowserTree(app);
        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setPreferredSize(JdfUi.size(500, 500));

        DialogBuilder.create("File Browser — Virtual File System")
                .withCenterComponent(scrollPane)
                .withOkButton()
                .withPreferredSize(550, 550)
                .showAndGet(ctx.getParentFrame());
    }
}
