package com.jdesk.frame.settings;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;
import com.jdesk.frame.boot.JdeskApplication;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsManagerDescriptorTest {

    @TempDir
    Path homeDir;

    @AfterEach
    void clearGlobalState() {
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app != null) {
            if (!app.isDisposed()) {
                app.shutdown();
            }
            ApplicationManager.clearApplication(app);
        }
        Disposer.clearAll();
        RuntimeParamRegistry.reset();
        PathManager.resetForTests();
    }

    @Test
    void descriptorBackedSettingStoresOnlyNonDefaultValues() {
        SettingsManager manager = new SettingsManager((Application) null);
        manager.registerSetting(SettingDescriptor.integerSetting(
                        "toolbar.ui.default.height", "Toolbar height", 36)
                .range(28, 56, 1)
                .build());

        manager.setSettingValue("toolbar.ui.default.height", 40);

        SettingsManager.StateData state = manager.getState();
        assertEquals(1, state.values.size());
        assertEquals("40", state.values.get(0).value);

        SettingsManager reloaded = new SettingsManager((Application) null);
        reloaded.loadState(state);
        reloaded.registerSetting(SettingDescriptor.integerSetting(
                        "toolbar.ui.default.height", "Toolbar height", 36)
                .range(28, 56, 1)
                .build());

        assertEquals(40, reloaded.getSettingValue("toolbar.ui.default.height"));

        reloaded.setSettingValue("toolbar.ui.default.height", 36);
        assertTrue(reloaded.getState().values.isEmpty());
    }

    @Test
    void settingsPageWithoutNameIsRejectedBeforeTouchingTheTree() {
        SettingsManager manager = new SettingsManager((Application) null);

        assertThrows(NullPointerException.class,
                () -> manager.registerSettingsPage("runtime", "Runtime", null, List.of()));
        assertTrue(manager.getRootGroup().getChildren().isEmpty(), "a nameless page must leave no empty tree node");
    }

    @Test
    void runtimeBindingRegistersAndUpdatesRuntimeParam() {
        PathManager.setHomePathForTests(homeDir);
        Application app = new JdeskApplication(SettingsManagerDescriptorTest.class.getClassLoader());
        app.init();

        SettingsManager manager = app.getService(SettingsManager.class);
        manager.registerSetting(SettingDescriptor.booleanSetting(
                        FrameRuntimeParams.FRAME_UI_COMPACT_MODE_KEY, "Compact mode", false)
                .runtimeBinding(FrameRuntimeParams.FRAME_UI_COMPACT_MODE)
                .build());

        assertEquals(false, RuntimeParamRegistry.getBoolean(FrameRuntimeParams.FRAME_UI_COMPACT_MODE_KEY));

        app.start();
        manager.setSettingValue(FrameRuntimeParams.FRAME_UI_COMPACT_MODE_KEY, true);

        assertEquals(true, RuntimeParamRegistry.getBoolean(FrameRuntimeParams.FRAME_UI_COMPACT_MODE_KEY));

        app.shutdown();
        assertTrue(RuntimeParamRegistry.getKeys().isEmpty());
    }
}
