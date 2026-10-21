package com.jdesk.frame.boot

import com.jdesk.core.app.Application
import com.jdesk.core.app.ApplicationManager
import com.jdesk.core.app.PathManager
import com.jdesk.core.disposer.Disposer
import com.jdesk.core.plugins.PluginManager
import com.jdesk.core.plugins.PluginState
import com.jdesk.core.runtime.params.CoreRuntimeParams
import com.jdesk.core.startup.StartupTrace
import com.jdesk.frame.action.ActionManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JdeskApplicationAsyncStartupTest {

    @TempDir
    lateinit var homeDir: Path

    @AfterEach
    fun clearGlobalState() {
        val app = ApplicationManager.getApplicationIfCreated()
        if (app != null) {
            if (!app.isDisposed) {
                app.shutdown()
            }
            ApplicationManager.clearApplication(app)
        }
        Disposer.clearAll()
        PathManager.resetForTests()
    }

    @Test
    fun initAsyncOverlapsPluginDescriptorLoadingWithSafeConfigWorkAndKeepsPluginOrdering() = runBlocking {
        enableStartupTraceReport()
        writePlugin("async-plugin", PLUGIN_ID)
        val loader = OverlapProbeClassLoader(JdeskApplicationAsyncStartupTest::class.java.classLoader)
        val app = JdeskApplication(loader)

        try {
            app.initAsync()

            assertTrue(
                loader.observedPluginLoadDuringFrameConfig.get(),
                "plugin descriptor loading should start while frame configuration is still loading",
            )
            assertEquals(Application.State.INITIALIZED, app.state)
            assertTrue(app.serviceContainer.hasService(ActionManager::class.java))

            val pluginManager = app.getService(PluginManager::class.java)
            assertNotNull(pluginManager.getPlugin(PLUGIN_ID))
            assertEquals(PluginState.LOADED, pluginManager.getPluginState(PLUGIN_ID))
            assertFalse(pluginManager.isPluginStarted(PLUGIN_ID))

            app.startAsync()

            assertEquals(Application.State.RUNNING, app.state)
            assertEquals(PluginState.STARTED, pluginManager.getPluginState(PLUGIN_ID))

            val events = app.getService(StartupTrace::class.java).events
            val parallelSpan = events.first { it.name == "application.init.parallel" }
            val pluginLoadSpan = events.first { it.name == "plugins.load" }
            val frameParseSpan = events.first { it.name == "frame.config.parse" }
            val appParseSpan = events.first { it.name == "application.config.parse" }
            val applySpan = events.first { it.name == "configuration.apply" }
            val initPluginsSpan = events.first { it.name == "plugins.init" }
            val containersSpan = events.first { it.name == "core.initializeContainers" }

            assertEquals(parallelSpan.id, pluginLoadSpan.parentId)
            assertEquals(parallelSpan.id, frameParseSpan.parentId)
            assertEquals(parallelSpan.id, appParseSpan.parentId)
            assertEquals(parallelSpan.id, applySpan.parentId)
            assertTrue(events.indexOf(applySpan) > events.indexOf(frameParseSpan))
            assertTrue(events.indexOf(applySpan) > events.indexOf(appParseSpan))
            assertTrue(events.indexOf(initPluginsSpan) > events.indexOf(applySpan))
            assertTrue(events.indexOf(containersSpan) > events.indexOf(initPluginsSpan))
        } finally {
            if (!app.isDisposed) {
                app.shutdown()
            }
        }
    }

    /** After startup the framework sweeps its own config directory in the background, not the app's data under home. */
    @Test
    fun startedApplicationSweepsStaleTempFilesUnderConfigDirOnly() = runBlocking {
        PathManager.setHomePathForTests(homeDir)
        val configTemp = staleTempFile(PathManager.getConfigDir().resolve(".jdesk-atomic-1.tmp"))
        val dataTemp = staleTempFile(homeDir.resolve("data").resolve(".jdesk-atomic-2.tmp"))
        val app = JdeskApplication(JdeskApplicationAsyncStartupTest::class.java.classLoader)

        try {
            app.initAsync()
            app.startAsync()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline &&
                (Files.exists(configTemp) || app.taskScope.tasksSnapshot.any { it.name == TEMP_FILE_SWEEP_TASK })
            ) {
                Thread.sleep(20)
            }
            assertFalse(Files.exists(configTemp), "a stale temp file under config should be swept after startup")
            assertTrue(Files.exists(dataTemp), "the framework must not sweep application data under home")
        } finally {
            if (!app.isDisposed) {
                app.shutdown()
            }
        }
    }

    private fun staleTempFile(file: Path): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, "stale")
        val stale = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10))
        Files.setLastModifiedTime(file, stale)
        Files.setAttribute(file, "basic:creationTime", stale) // The sweep uses the later of the two times.
        return file
    }

    private fun enableStartupTraceReport() {
        val systemDir = homeDir.resolve("system")
        Files.createDirectories(systemDir)
        Files.writeString(
            systemDir.resolve("application.properties"),
            CoreRuntimeParams.STARTUP_TRACE_MODE_KEY + "=report\n",
        )
        PathManager.setHomePathForTests(homeDir)
    }

    private fun writePlugin(directoryName: String, pluginId: String) {
        val metaInf = homeDir.resolve("plugins").resolve(directoryName).resolve("META-INF")
        Files.createDirectories(metaInf)
        Files.writeString(
            metaInf.resolve("plugin.xml"),
            """
            <?xml version="1.0"?>
            <deskframe-plugin>
              <id>$pluginId</id>
              <name>$pluginId</name>
              <version>1.0.0</version>
            </deskframe-plugin>
            """.trimIndent(),
        )
    }

    private class OverlapProbeClassLoader(parent: ClassLoader) : ClassLoader(parent) {
        val observedPluginLoadDuringFrameConfig = AtomicBoolean(false)

        override fun getResourceAsStream(name: String): InputStream? {
            if (name == FRAME_CONFIG_RESOURCE) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (System.nanoTime() < deadline) {
                    val app = ApplicationManager.getApplicationIfCreated()
                    val trace = app?.serviceContainer?.getServiceIfCreated(StartupTrace::class.java)
                    if (trace?.events?.any { event ->
                            event.name == "plugins.load" ||
                                event.name.startsWith("plugin.descriptor.parse:")
                        } == true
                    ) {
                        observedPluginLoadDuringFrameConfig.set(true)
                        break
                    }
                    Thread.sleep(10)
                }
            }
            return parent.getResourceAsStream(name)
        }
    }

    companion object {
        private const val FRAME_CONFIG_RESOURCE = "META-INF/frame-plugins.xml"
        private const val PLUGIN_ID = "test.async.plugin"
    }
}
