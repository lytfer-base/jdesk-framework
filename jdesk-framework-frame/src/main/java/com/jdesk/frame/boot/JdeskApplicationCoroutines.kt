package com.jdesk.frame.boot

import com.jdesk.concurrency.startup.JdeskStartupScope
import com.jdesk.core.app.Application
import com.jdesk.core.app.ApplicationInfo
import com.jdesk.core.app.ApplicationManager
import com.jdesk.core.app.PathManager
import com.jdesk.core.io.AtomicFiles
import com.jdesk.core.runtime.RuntimeParamRegistry
import com.jdesk.core.startup.suspendChildSpan
import com.jdesk.core.startup.suspendSpan
import com.jdesk.frame.i18n.SwingLocaleListener
import com.jdesk.frame.laf.JdfLafManager
import com.jdesk.frame.ui.scale.JdfScale
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Java-visible blocking bridges for the coroutine-first startup API.
 */
object JdeskApplicationCoroutines {

    @JvmStatic
    fun initBlocking(application: JdeskApplication) {
        runBlocking(CoroutineName("application-init-blocking")) {
            application.initAsync()
        }
    }

    @JvmStatic
    fun startBlocking(application: JdeskApplication) {
        runBlocking(CoroutineName("application-start-blocking")) {
            application.startAsync()
        }
    }

    @JvmStatic
    fun launchBlocking(
        builder: JdeskApplicationBuilder,
        exitOnStartupFailure: Boolean,
    ): JdeskApplication {
        return runBlocking(CoroutineName("frame-launch-blocking")) {
            builder.launchAsync(exitOnStartupFailure)
        }
    }
}

suspend fun JdeskApplication.initAsync() {
    if (state != Application.State.NOT_INITIALIZED) {
        throw IllegalStateException("Application already initialized: $state")
    }

    val startupTrace = startupTrace
    val startupScope = JdeskStartupScope("application-init")
    try {
        startupTrace.suspendSpan("application.init") {
            ApplicationManager.setApplication(this@initAsync)
            try {
                transitionTo(Application.State.INITIALIZING)
                publishLifecycleEvent("beforeApplicationLoaded") { listener ->
                    listener.beforeApplicationLoaded(this@initAsync)
                }
                LOG.info("JDesk application initializing...")
                startupTrace.suspendSpan("path.plugins.resolve") {
                    LOG.info("Plugins directory: {}", PathManager.getPluginsDir())
                }

                JdeskApplication.installFrameParentFirstPackage()

                startupTrace.suspendSpan("application.init.parallel") {
                    val parentId = startupTrace.currentSpanId
                    core.initializeCoreServices()

                    val pluginLoad = startupScope.asyncIo("plugins.load") {
                        startupTrace.suspendChildSpan(parentId, "plugins.load") {
                            core.loadPlugins()
                        }
                    }
                    val frameConfigParse = startupScope.asyncDefault("frame.config.parse") {
                        startupTrace.suspendChildSpan(parentId, "frame.config.parse") {
                            parseFrameConfiguration()
                        }
                    }
                    val applicationConfigParse = startupScope.asyncDefault("application.config.parse") {
                        startupTrace.suspendChildSpan(parentId, "application.config.parse") {
                            parseApplicationConfiguration()
                        }
                    }

                    val frameConfig = frameConfigParse.await()
                    val applicationConfig = applicationConfigParse.await()
                    pluginLoad.await()
                    startupTrace.suspendChildSpan(parentId, "configuration.apply") {
                        applyFrameConfiguration(frameConfig)
                        applyApplicationConfiguration(applicationConfig)
                    }
                }

                core.initPlugins()
                core.initializeContainers()

                transitionTo(Application.State.INITIALIZED)
                publishLifecycleEvent("applicationInitialized") { listener ->
                    listener.applicationInitialized(this@initAsync)
                }
                LOG.info(
                    "Application initialized in {}ms ({} service descriptors registered)",
                    System.currentTimeMillis() - startTimeMillis,
                    core.registeredServiceCount,
                )
            } catch (failure: Throwable) {
                ApplicationManager.clearApplication(this@initAsync)
                throw failure
            }
        }
    } finally {
        startupScope.close()
    }
}

suspend fun JdeskApplication.startAsync() {
    startupTrace.suspendSpan("application.start") {
        startRuntimeAsync()
        finishStartBeforeUiShown()
    }
}

private suspend fun JdeskApplication.startRuntimeAsync() {
    if (state != Application.State.INITIALIZED) {
        throw IllegalStateException("Must call init() before start(). Current: $state")
    }

    transitionTo(Application.State.STARTING)
    LOG.info("Starting application runtime...")

    RuntimeParamRegistry.completeBoot()
    withContext(Dispatchers.Default) {
        core.startPlugins()
    }
}

private fun JdeskApplication.finishStartBeforeUiShown() {
    publishLifecycleEvent("beforeUiShown") { listener ->
        listener.beforeUiShown(this@finishStartBeforeUiShown)
    }

    transitionTo(Application.State.RUNNING)
    LOG.info("Application started.")
    sweepStaleTempFilesInBackground()

    publishLifecycleEvent("onApplicationStarted") { listener ->
        listener.onApplicationStarted(this@finishStartBeforeUiShown)
    }
}

/**
 * Sweeps atomic-write temp files left by killed processes from the config directory, in the background.
 * Don't await it on the startup path; applications sweep their own data directories.
 */
private fun JdeskApplication.sweepStaleTempFilesInBackground() {
    taskScope.launchIo(TEMP_FILE_SWEEP_TASK) {
        val configDir = PathManager.getConfigDir()
        val swept = AtomicFiles.sweepStaleTempFiles(configDir)
        if (swept > 0) {
            LOG.info("Swept {} stale atomic temp file(s) under {}", swept, configDir)
        }
    }
}

internal const val TEMP_FILE_SWEEP_TASK = "io.tempfiles.sweep"

@Suppress("unused")
suspend fun JdeskApplicationBuilder.launchAsync(): JdeskApplication {
    return launchAsync(exitOnStartupFailure = false)
}

internal suspend fun JdeskApplicationBuilder.launchAsync(
    exitOnStartupFailure: Boolean,
): JdeskApplication {
    val frameApplication = try {
        JdeskApplication(resolveApplicationClassLoader())
    } catch (failure: Throwable) {
        // Nothing to roll back when construction fails (say, a bad escape in application.properties); still report it.
        if (exitOnStartupFailure) {
            JdeskApplicationBuilder.reportStartupFailureAndExit(failure)
        }
        throw failure
    }
    val startupTrace = frameApplication.startupTrace
    val startupScope = JdeskStartupScope("frame-launch")

    try {
        val application = frameApplication as Application
        startupTrace.suspendSpan("frame.launch") {
            startupTrace.suspendSpan("frame.scale.installEarly") {
                JdfScale.installEarly()
            }

            startupTrace.suspendSpan("frame.launch.parallel") {
                val parentId = startupTrace.currentSpanId
                val lookAndFeel = startupScope.asyncEdt("frame.lookAndFeel.ensure") {
                    startupTrace.suspendChildSpan(parentId, "frame.lookAndFeel.ensure") {
                        JdeskApplication.ensureLookAndFeel(startupTrace)
                    }
                }

                frameApplication.initAsync()
                installComponentStoreIfNeeded(application)
                lookAndFeel.await()
            }

            startupTrace.suspendSpan("frame.theme.applyConfigured") {
                withContext(Dispatchers.Swing) {
                    application.getService(JdfLafManager::class.java).applyConfiguredTheme()
                }
            }
            SwingLocaleListener.install(application)

            val effectiveTitle = configuredTitle
                ?: application.getService(ApplicationInfo::class.java).fullProductName
            val context = startupTrace.suspendSpan("frame.context.create") {
                withContext(Dispatchers.Swing) {
                    frameApplication.createApplicationContext(effectiveTitle)
                }
            }
            val mainFrame = context.mainFrame
            val activityRunner = ApplicationActivityRunner(frameApplication, startupTrace)

            startupTrace.suspendSpan("application.startRuntime") {
                frameApplication.startRuntimeAsync()
            }

            startupTrace.suspendSpan("application.activities") {
                withContext(Dispatchers.Swing) {
                    activityRunner.runApplicationActivities(
                        context,
                        applicationActivitiesSnapshot,
                    )
                }
            }

            startupTrace.suspendSpan("application.finishStartBeforeUiShown") {
                frameApplication.finishStartBeforeUiShown()
            }

            if (shouldShowMainFrameOnLaunch()) {
                startupTrace.suspendSpan("frame.ui.show") {
                    withContext(Dispatchers.Swing) {
                        mainFrame.isVisible = true
                        startupTrace.markFirstWindowVisible()
                    }
                }
            }

            activityRunner.schedulePostStartupActivities(
                context,
                postStartupActivitiesSnapshot,
            )
        }
        finishStartupTrace(startupTrace)
        return frameApplication
    } catch (failure: Throwable) {
        val effectiveFailure = failure.unwrapRecovered()
        rollbackStartup(frameApplication, effectiveFailure)
        try {
            finishStartupTrace(startupTrace)
        } catch (traceFailure: Throwable) {
            effectiveFailure.addSuppressed(traceFailure)
        }
        if (exitOnStartupFailure) {
            JdeskApplicationBuilder.reportStartupFailureAndExit(effectiveFailure)
        }
        throw effectiveFailure
    } finally {
        startupScope.close()
    }
}

/** Unwraps a copy made by coroutine stack-trace recovery: the original is the cause with the same type and message. */
private fun Throwable.unwrapRecovered(): Throwable {
    val original = cause
    return if (original != null &&
        original.javaClass == javaClass &&
        original.message == message
    ) {
        original
    } else {
        this
    }
}

private val LOG = LoggerFactory.getLogger(JdeskApplication::class.java)
