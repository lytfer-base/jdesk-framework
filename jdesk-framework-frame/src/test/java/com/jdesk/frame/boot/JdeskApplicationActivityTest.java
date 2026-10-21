package com.jdesk.frame.boot;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdeskApplicationActivityTest {

    @TempDir
    Path homeDir;

    static final List<String> events = new CopyOnWriteArrayList<>();

    @AfterEach
    void clearGlobalState() {
        events.clear();
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app != null && !app.isDisposed()) {
            app.shutdown();
        }
        if (app != null) {
            ApplicationManager.clearApplication(app);
        }
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void applicationActivitiesRunBeforeFirstWindowVisibleAndBeforeStartedCallback() {
        PathManager.setHomePathForTests(homeDir);

        JdeskApplication app = JdeskApplicationBuilder.create("Activity Test")
                .applicationActivity(context -> {
                    assertFalse(context.getMainFrame().isVisible());
                    events.add("applicationActivity");
                })
                .postStartupActivity(context -> events.add("startedCallback"))
                .launch();

        try {
            assertEquals(Application.State.RUNNING, app.getState());
            assertEquals(List.of("applicationActivity"), events.subList(0, 1));
        } finally {
            app.shutdown();
        }
    }

    @Test
    void applicationActivityFailureRollsBackStartup() {
        PathManager.setHomePathForTests(homeDir);
        RuntimeException failure = new RuntimeException("activity failed");

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                JdeskApplicationBuilder.create("Failure Test")
                        .applicationActivity(context -> {
                            throw failure;
                        })
                        .launch());

        assertSame(failure, thrown);
        assertNull(ApplicationManager.getApplicationIfCreated());
    }

    @Test
    void appConfigCanContributeApplicationAndPostStartupActivities() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        String xml = """
                <?xml version="1.0"?>
                <app-plugins>
                  <extensions>
                    <extension point="deskframe.applicationActivity"
                               implementation="%s"/>
                    <extension point="deskframe.postStartupActivity"
                               implementation="%s"/>
                  </extensions>
                </app-plugins>
                """.formatted(
                ConfigApplicationActivity.class.getName(),
                ConfigPostStartupActivity.class.getName());

        ClassLoader loader = new ActivityConfigClassLoader(
                JdeskApplicationActivityTest.class.getClassLoader(),
                xml);

        JdeskApplication app = JdeskApplicationBuilder.create("Config Activity Test")
                .applicationClassLoader(loader)
                .launch();

        try {
            waitUntilEvent("configPostStartupActivity");

            assertTrue(events.contains("configApplicationActivity"));
            assertTrue(events.contains("configPostStartupActivity"));
        } finally {
            app.shutdown();
        }
    }

    @Test
    void postStartupActivityFailureDoesNotStopLaterActivitiesOrApplication() throws Exception {
        PathManager.setHomePathForTests(homeDir);

        JdeskApplication app = JdeskApplicationBuilder.create("Post Failure Test")
                .postStartupActivity(context -> {
                    events.add("failingPostStartup");
                    throw new RuntimeException("post failed");
                })
                .postStartupActivity(context -> events.add("healthyPostStartup"))
                .launch();

        try {
            waitUntilEvent("healthyPostStartup");

            assertEquals(Application.State.RUNNING, app.getState());
            assertTrue(events.contains("failingPostStartup"));
            assertTrue(events.contains("healthyPostStartup"));
        } finally {
            app.shutdown();
        }
    }

    private void waitUntilEventCount(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (events.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private void waitUntilEvent(String event) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!events.contains(event) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    public static final class ConfigApplicationActivity implements ApplicationActivity {
        @Override
        public void runActivity(ApplicationContext context) {
            events.add("configApplicationActivity");
        }
    }

    public static final class ConfigPostStartupActivity implements PostStartupActivity {
        @Override
        public void runActivity(ApplicationContext context) {
            events.add("configPostStartupActivity");
        }
    }

    private static final class ActivityConfigClassLoader extends ClassLoader {
        private final String appPluginsXml;

        ActivityConfigClassLoader(ClassLoader parent, String appPluginsXml) {
            super(parent);
            this.appPluginsXml = appPluginsXml;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if ("META-INF/app-plugins.xml".equals(name)) {
                return new ByteArrayInputStream(appPluginsXml.getBytes(StandardCharsets.UTF_8));
            }
            return super.getResourceAsStream(name);
        }
    }
}
