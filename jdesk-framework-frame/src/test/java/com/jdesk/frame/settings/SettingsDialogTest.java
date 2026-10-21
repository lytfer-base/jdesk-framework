package com.jdesk.frame.settings;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.config.ComponentStore;
import com.jdesk.core.config.PersistentStateComponent;
import com.jdesk.core.config.State;
import com.jdesk.core.config.Storage;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.UiLanguage;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.frame.boot.JdeskApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.CellRendererPane;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Page lifecycle of {@link SettingsDialog}: only pages opened this time are applied, cancelled edits don't carry over
 * to the next opening, and an open dialog follows language switches.
 */
class SettingsDialogTest {

    @BeforeEach
    void registerFrameMessages() {
        I18n.resetForTests();
        I18n.registerRoot("jdesk/i18n", SettingsDialogTest.class.getClassLoader(), UiLanguage.EN);
    }

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
        I18n.resetForTests();
    }

    /** OK writes changed settings to disk at once, not on close or exit, so a killed process loses nothing. */
    @Test
    void okWritesTheAppliedSettingsToDiskRightAway(@TempDir Path homeDir) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        PathManager.setHomePathForTests(homeDir);
        Application app = new JdeskApplication(SettingsDialogTest.class.getClassLoader());
        app.init();
        Path configDir = homeDir.resolve("probe-config");
        ComponentStore store = new ComponentStore(configDir);
        app.getServiceContainer().registerInstance(ComponentStore.class, store);
        ProbeComponent probe = new ProbeComponent();
        store.register(probe);
        ProbePage page = new ProbePage(probe);
        SettingsManager manager = new SettingsManager(app);
        manager.getRootGroup().add(page);

        SwingUtilities.invokeAndWait(() -> {
            SettingsDialog dialog = manager.createDialog(null);
            try {
                select(dialog, page);
                page.field.setText("b");
                click(dialog, I18n.message("frame.dialog.ok"));
            } finally {
                dialog.dispose();
            }
        });

        ProbeComponent reloaded = new ProbeComponent();
        new ComponentStore(configDir).register(reloaded);
        assertEquals("b", reloaded.value);
    }

    @Test
    void okAppliesOpenedPagesAndSkipsPagesNeverOpened() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        FieldPage first = new FieldPage("First", "a");
        FieldPage second = new FieldPage("Second", "b");
        SettingsGroup root = rootWith(first, second);

        SwingUtilities.invokeAndWait(() -> {
            SettingsDialog dialog = new SettingsDialog(null, root);
            try {
                dialog.pack();
                assertTrue(dialog.isDisplayable());
                first.field.setText("a2");
                click(dialog, I18n.message("frame.dialog.ok"));
                assertEquals("a2", first.saved);
                assertEquals(0, second.applies, "a page never opened should not be checked or applied");
                assertFalse(dialog.isDisplayable(), "OK must close the dialog");
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    void editCancelledInOneDialogIsNotAppliedByTheNext() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        FieldPage first = new FieldPage("First", "a");
        FieldPage second = new FieldPage("Second", "b");
        SettingsGroup root = rootWith(first, second);

        SwingUtilities.invokeAndWait(() -> {
            SettingsDialog dialog = new SettingsDialog(null, root);
            try {
                select(dialog, second);
                second.field.setText("b2");
                click(dialog, I18n.message("frame.dialog.cancel"));
            } finally {
                dialog.dispose();
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            SettingsDialog dialog = new SettingsDialog(null, root);
            try {
                select(dialog, second);
                click(dialog, I18n.message("frame.dialog.ok"));
            } finally {
                dialog.dispose();
            }
        });

        assertEquals("b", second.saved, "an edit cancelled earlier must not be applied by the next OK");
        assertEquals(0, second.applies);
    }

    /**
     * A language switch while the dialog is open updates the chrome, tree names, header and an unmodified current
     * page, and keeps the selection.
     */
    @Test
    void openDialogFollowsALanguageSwitch(@TempDir Path homeDir) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        startApplicationInChinese(homeDir);
        KeyedPage page = new KeyedPage(false);
        SettingsGroup group = new SettingsGroup("group", () -> I18n.message("frame.settings.group.appearance"));
        group.add(page);
        SettingsGroup root = new SettingsGroup("root", "").addGroup(group);
        AtomicReference<SettingsDialog> dialog = new AtomicReference<>();
        AtomicInteger groupRowWidth = new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> {
                dialog.set(new SettingsDialog(null, root));
                assertEquals("设置", dialog.get().getTitle());
                groupRowWidth.set(tree(dialog.get()).getRowBounds(0).width);
            });

            I18n.switchTo(UiLanguage.EN);

            SwingUtilities.invokeAndWait(() -> {
                SettingsDialog open = dialog.get();
                assertEquals("Settings", open.getTitle());
                find(open, AbstractButton.class, button -> "Cancel".equals(button.getText()));
                find(open, JLabel.class, label -> "Theme".equals(label.getText()));
                find(open, JLabel.class, label -> "Theme:".equals(label.getText()));
                JTree tree = tree(open);
                assertTrue(tree.getRowBounds(0).width > groupRowWidth.get(), "the row must widen for the longer name");
                assertEquals(page, ((DefaultMutableTreeNode) tree.getLastSelectedPathComponent()).getUserObject());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog.get() != null) {
                    dialog.get().dispose();
                }
            });
        }
    }

    /** With unapplied changes on the current page, a language switch only updates the header so input isn't lost. */
    @Test
    void languageSwitchKeepsACurrentPageWithUnappliedChanges(@TempDir Path homeDir) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        startApplicationInChinese(homeDir);
        KeyedPage page = new KeyedPage(true);
        AtomicReference<SettingsDialog> dialog = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> dialog.set(new SettingsDialog(null, rootWith(page))));

            I18n.switchTo(UiLanguage.EN);

            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, page.builds, "a page with unapplied changes must not be rebuilt");
                find(dialog.get(), JLabel.class, label -> "Theme".equals(label.getText()));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog.get() != null) {
                    dialog.get().dispose();
                }
            });
        }
    }

    /**
     * When OK applies a language and the dialog closes, the refresh queued after it returns early instead of rebuilding
     * the page and starting a timer nobody stops.
     */
    @Test
    void refreshQueuedByOkDoesNotTouchTheClosedDialog(@TempDir Path homeDir) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        startApplicationInChinese(homeDir);
        LanguageApplyingPage page = new LanguageApplyingPage();
        SwingUtilities.invokeAndWait(() -> {
            SettingsDialog dialog = new SettingsDialog(null, rootWith(page));
            try {
                click(dialog, I18n.message("frame.dialog.ok"));
            } finally {
                dialog.dispose();
            }
        });
        SwingUtilities.invokeAndWait(() -> {
        });

        assertEquals(UiLanguage.EN, I18n.current(), "OK should apply the language page first");
        assertEquals(1, page.builds, "a closed dialog must not rebuild its page");
    }

    private static void startApplicationInChinese(Path homeDir) {
        PathManager.setHomePathForTests(homeDir);
        new JdeskApplication(SettingsDialogTest.class.getClassLoader()).init();
        I18n.initialize(UiLanguage.ZH_CN);
    }

    private static JTree tree(SettingsDialog dialog) {
        return find(dialog.getContentPane(), JTree.class, any -> true);
    }

    private static SettingsGroup rootWith(Configurable... pages) {
        SettingsGroup group = new SettingsGroup("group", "Group");
        for (Configurable page : pages) {
            group.add(page);
        }
        return new SettingsGroup("root", "").addGroup(group);
    }

    private static void click(Container container, String text) {
        find(container, AbstractButton.class, button -> text.equals(button.getText())).doClick();
    }

    private static void select(SettingsDialog dialog, Configurable page) {
        JTree tree = tree(dialog);
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            if (((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() == page) {
                tree.setSelectionPath(path);
                return;
            }
        }
        fail("not in the settings tree: " + page.getDisplayName());
    }

    private static <T extends Component> T find(Container container, Class<T> type, Predicate<T> match) {
        T found = findOrNull(container, type, match);
        if (found == null) {
            fail("component not found: " + type.getSimpleName());
        }
        return found;
    }

    private static <T extends Component> T findOrNull(Container container, Class<T> type, Predicate<T> match) {
        for (Component child : container.getComponents()) {
            if (child instanceof CellRendererPane) {
                continue; // Cell renderers aren't visible controls; don't assert on them.
            }
            if (type.isInstance(child) && match.test(type.cast(child))) {
                return type.cast(child);
            }
            if (child instanceof Container nested) {
                T found = findOrNull(nested, type, match);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Persistence probe whose state is a single string. */
    @State(name = "SettingsSaveProbe", storages = @Storage("settings-save-probe.xml"))
    public static final class ProbeComponent implements PersistentStateComponent<ProbeComponent.ProbeState> {
        volatile String value = "a";

        @Override
        public ProbeState getState() {
            ProbeState state = new ProbeState();
            state.value = value;
            return state;
        }

        @Override
        public void loadState(ProbeState state) {
            value = state == null || state.value == null ? "a" : state.value;
        }

        @Override
        public ProbeState getDefaultState() {
            return new ProbeState();
        }

        public static final class ProbeState {
            public String value = "a";
        }
    }

    /** Writes the text field's value into the probe on apply. */
    private static final class ProbePage implements Configurable {
        private final ProbeComponent probe;
        JTextField field;

        ProbePage(ProbeComponent probe) {
            this.probe = probe;
        }

        @Override
        public String getDisplayName() {
            return "Probe";
        }

        @Override
        public JComponent createComponent() {
            if (field == null) {
                field = new JTextField(probe.value);
            }
            return field;
        }

        @Override
        public boolean isModified() {
            return !field.getText().equals(probe.value);
        }

        @Override
        public void apply() {
            probe.value = field.getText();
        }

        @Override
        public void reset() {
            field.setText(probe.value);
        }
    }

    /** Builds its field on the first createComponent and reuses it; isModified before that throws an NPE. */
    private static final class FieldPage implements Configurable {
        private final String name;
        String saved;
        JTextField field;
        int applies;

        FieldPage(String name, String saved) {
            this.name = name;
            this.saved = saved;
        }

        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public JComponent createComponent() {
            if (field == null) {
                field = new JTextField(saved);
            }
            return field;
        }

        @Override
        public boolean isModified() {
            return !field.getText().equals(saved);
        }

        @Override
        public void apply() {
            saved = field.getText();
            applies++;
        }

        @Override
        public void reset() {
            field.setText(saved);
        }
    }

    /** Looks up its name and text by key on each call; builds counts createComponent calls and modified is fixed. */
    private static final class KeyedPage implements Configurable {
        private final boolean modified;
        int builds;

        KeyedPage(boolean modified) {
            this.modified = modified;
        }

        @Override
        public String getDisplayName() {
            return I18n.message("frame.settings.theme.title");
        }

        @Override
        public JComponent createComponent() {
            builds++;
            return new JLabel(I18n.message("frame.settings.theme.label"));
        }

        @Override
        public boolean isModified() {
            return modified;
        }

        @Override
        public void apply() {
        }

        @Override
        public void reset() {
        }
    }

    /** Switches the UI language on apply; modified until applied; builds counts createComponent calls. */
    private static final class LanguageApplyingPage implements Configurable {
        private boolean applied;
        int builds;

        @Override
        public String getDisplayName() {
            return "Language";
        }

        @Override
        public JComponent createComponent() {
            builds++;
            return new JLabel();
        }

        @Override
        public boolean isModified() {
            return !applied;
        }

        @Override
        public void apply() {
            applied = true;
            I18n.switchTo(UiLanguage.EN);
        }

        @Override
        public void reset() {
        }
    }
}
