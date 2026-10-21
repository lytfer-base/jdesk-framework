package com.jdesk.frame.boot;

import com.jdesk.core.app.ApplicationTask;
import com.jdesk.core.extensions.ExtensionRegistry;
import com.jdesk.core.startup.StartupTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

final class ApplicationActivityRunner {

    static final String APPLICATION_ACTIVITY_POINT = "deskframe.applicationActivity";
    static final String POST_STARTUP_ACTIVITY_POINT = "deskframe.postStartupActivity";

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationActivityRunner.class);

    private final JdeskApplication application;
    private final StartupTrace startupTrace;

    ApplicationActivityRunner(JdeskApplication application, StartupTrace startupTrace) {
        this.application = Objects.requireNonNull(application, "application");
        this.startupTrace = Objects.requireNonNull(startupTrace, "startupTrace");
    }

    void runApplicationActivities(ApplicationContext context,
                                  List<ApplicationActivity> builderActivities) {
        startupTrace.runSpan("application.activities.run", () -> {
            runExtensionApplicationActivities(context);
            for (ApplicationActivity activity : builderActivities) {
                runApplicationActivity(context, "builder:" + activity.getClass().getName(), activity);
            }
        });
    }

    ApplicationTask<Void> schedulePostStartupActivities(ApplicationContext context,
                                                        List<PostStartupActivity> builderActivities) {
        return application.getTaskScope().launchEdt("postStartup.activities", () -> {
            if (application.isDisposed()) {
                return;
            }
            startupTrace.runSpan("postStartup.activities.run", () -> {
                runExtensionPostStartupActivities(context);
                for (PostStartupActivity activity : builderActivities) {
                    runPostStartupActivity(context, "builder:" + activity.getClass().getName(), activity);
                }
            });
        });
    }

    private void runExtensionApplicationActivities(ApplicationContext context) {
        ExtensionRegistry registry = application.getExtensionRegistry();
        List<ApplicationActivity> activities = registry.getExtensions(APPLICATION_ACTIVITY_POINT);
        for (ApplicationActivity activity : activities) {
            runApplicationActivity(context, activity.getClass().getName(), activity);
        }
    }

    private void runExtensionPostStartupActivities(ApplicationContext context) {
        ExtensionRegistry registry = application.getExtensionRegistry();
        List<PostStartupActivity> activities = registry.getExtensions(POST_STARTUP_ACTIVITY_POINT);
        for (PostStartupActivity activity : activities) {
            runPostStartupActivity(context, activity.getClass().getName(), activity);
        }
    }

    private void runApplicationActivity(ApplicationContext context, String name, ApplicationActivity activity) {
        startupTrace.runSpan("application.activity:" + name, () -> activity.runActivity(context));
    }

    private void runPostStartupActivity(ApplicationContext context, String name, PostStartupActivity activity) {
        try {
            startupTrace.runSpan("postStartup.activity:" + name, () -> activity.runActivity(context));
        } catch (RuntimeException | Error e) {
            LOG.error("Post-startup activity failed: {}", name, e);
        }
    }
}
