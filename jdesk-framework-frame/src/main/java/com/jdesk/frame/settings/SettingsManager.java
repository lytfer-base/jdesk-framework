package com.jdesk.frame.settings;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.config.ComponentStore;
import com.jdesk.core.config.PersistentStateComponent;
import com.jdesk.core.config.State;
import com.jdesk.core.config.Storage;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.i18n.I18n;
import com.jdesk.frame.i18n.LanguageSettingsConfigurable;
import com.jdesk.frame.laf.JdfLafManager;
import com.jdesk.frame.laf.ThemeSettingsConfigurable;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.runtime.RuntimeParamSource;

import javax.swing.JFrame;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Application-wide registry of settings groups, pages and descriptor-driven settings. Hand-written pages join through
 * {@link #registerConfigurable(String, Supplier, Configurable)}, and {@link SettingDescriptor}s get generated pages.
 * Values are saved as strings and parsed and validated by their descriptor when read. A setting bound to a core
 * runtime parameter is pushed to {@code RuntimeParamRegistry} on registration and on every user change.
 */
@State(name = "JdfSettings", storages = @Storage("jdf-settings.xml"))
public class SettingsManager implements Disposable, PersistentStateComponent<SettingsManager.StateData> {

    /** Built-in Appearance group (theme, language). */
    public static final String APPEARANCE_GROUP_ID = "appearance";
    /** General group, where descriptor settings go by default. */
    public static final String GENERAL_GROUP_ID = "general";

    private static final Supplier<String> APPEARANCE_GROUP_NAME =
            () -> I18n.message("frame.settings.group.appearance");

    /** Root of the settings tree; never shown, it only holds the groups. */
    private final SettingsGroup rootGroup = new SettingsGroup("root", "");

    private final Map<String, SettingsGroup> groups = new LinkedHashMap<>();

    private final Map<String, SettingDescriptor> descriptors = new LinkedHashMap<>();

    /** Generated page per descriptor group ID. */
    private final Map<String, DescriptorSettingsConfigurable> generatedPages = new LinkedHashMap<>();

    /** Holds only the values that differ from their default. */
    private final Map<String, String> values = new ConcurrentHashMap<>();

    private final Application application;

    public SettingsManager() {
        this(ApplicationManager.getApplicationIfCreated());
    }

    public SettingsManager(Application application) {
        this.application = application;
        groups.put(rootGroup.getId(), rootGroup);
        registerBuiltInConfigurables();
    }

    public SettingsGroup getRootGroup() {
        return rootGroup;
    }

    /**
     * Gets or creates a group with a fixed name; use {@link #getOrCreateGroup(String, Supplier)} for a name that
     * follows the UI language.
     */
    public SettingsGroup getOrCreateGroup(String id, String displayName) {
        return getOrCreateGroup(id, () -> displayName);
    }

    /**
     * Gets or creates a group under the root, shown in creation order; the first caller for an ID sets its name, and
     * later registrations with that ID just add pages.
     */
    public SettingsGroup getOrCreateGroup(String id, Supplier<String> displayName) {
        return groups.computeIfAbsent(id, key -> {
            SettingsGroup group = new SettingsGroup(id, displayName);
            rootGroup.addGroup(group);
            return group;
        });
    }

    /** Registers a hand-written page in a group with a fixed name. */
    public void registerConfigurable(String groupId, String groupDisplayName, Configurable configurable) {
        registerConfigurable(groupId, () -> groupDisplayName, configurable);
    }

    /** Registers a hand-written page; the {@link Configurable} owns its UI and saving, the manager just the tree. */
    public void registerConfigurable(String groupId, Supplier<String> groupDisplayName, Configurable configurable) {
        Objects.requireNonNull(configurable, "configurable");
        getOrCreateGroup(groupId, groupDisplayName).add(configurable);
    }

    /**
     * Registers a descriptor-driven setting on its group's generated page; a runtime binding creates or updates the
     * parameter in {@code RuntimeParamRegistry}.
     *
     * @throws IllegalStateException if the setting ID is already registered
     */
    public synchronized void registerSetting(SettingDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptors.containsKey(descriptor.getId())) {
            throw new IllegalStateException("Setting already registered: " + descriptor.getId());
        }

        descriptors.put(descriptor.getId(), descriptor);
        DescriptorSettingsConfigurable page = generatedPages.computeIfAbsent(
                descriptor.getGroupId(),
                groupId -> {
                    DescriptorSettingsConfigurable configurable = new DescriptorSettingsConfigurable(
                            this,
                            descriptor::getGroupDisplayName,
                            "");
                    getOrCreateGroup(descriptor.getGroupId(), descriptor::getGroupDisplayName).add(configurable);
                    return configurable;
                });
        page.addDescriptor(descriptor);
        bridgeDescriptorToRuntime(descriptor);
    }

    /** Registers a page of descriptor-driven settings, with fixed group and page names. */
    public void registerSettingsPage(String groupId,
                                     String groupDisplayName,
                                     String pageDisplayName,
                                     Collection<SettingDescriptor> pageDescriptors) {
        Objects.requireNonNull(pageDisplayName, "pageDisplayName");
        registerSettingsPage(groupId, () -> groupDisplayName, () -> pageDisplayName, pageDescriptors);
    }

    /** Registers a page of related descriptor-driven settings, each indexed by ID and added in the given order. */
    public synchronized void registerSettingsPage(String groupId,
                                                  Supplier<String> groupDisplayName,
                                                  Supplier<String> pageDisplayName,
                                                  Collection<SettingDescriptor> pageDescriptors) {
        Objects.requireNonNull(pageDescriptors, "pageDescriptors");
        DescriptorSettingsConfigurable page = new DescriptorSettingsConfigurable(this, pageDisplayName, "");
        getOrCreateGroup(groupId, groupDisplayName).add(page);

        for (SettingDescriptor descriptor : pageDescriptors) {
            if (descriptors.containsKey(descriptor.getId())) {
                throw new IllegalStateException("Setting already registered: " + descriptor.getId());
            }
            descriptors.put(descriptor.getId(), descriptor);
            page.addDescriptor(descriptor);
            bridgeDescriptorToRuntime(descriptor);
        }
    }

    /** Returns a snapshot of the registered setting descriptors. */
    public List<SettingDescriptor> getSettingDescriptors() {
        return List.copyOf(descriptors.values());
    }

    /**
     * Returns the descriptor of a setting.
     *
     * @throws IllegalArgumentException if the setting ID isn't registered
     */
    public SettingDescriptor getSettingDescriptor(String id) {
        SettingDescriptor descriptor = descriptors.get(id);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown setting: " + id);
        }
        return descriptor;
    }

    /**
     * Returns a setting's value parsed by its descriptor, or its default if none is saved; the value's type follows
     * the {@link SettingType}.
     */
    public Object getSettingValue(String id) {
        SettingDescriptor descriptor = getSettingDescriptor(id);
        return descriptor.parse(values.get(id));
    }

    /** Returns a setting's value formatted as its saved string. */
    public String getSettingValueString(String id) {
        SettingDescriptor descriptor = getSettingDescriptor(id);
        return descriptor.format(getSettingValue(id));
    }

    /**
     * Sets a value after the descriptor normalizes and validates it; a value equal to the default is removed from the
     * saved values, and a runtime binding pushes it to {@code RuntimeParamRegistry}.
     */
    public void setSettingValue(String id, Object value) {
        SettingDescriptor descriptor = getSettingDescriptor(id);
        Object normalizedValue = descriptor.normalizeValue(value);
        String serializedValue = descriptor.format(normalizedValue);
        String defaultValue = descriptor.format(descriptor.getDefaultValue());

        if (serializedValue.equals(defaultValue)) {
            values.remove(id);
        } else {
            values.put(id, serializedValue);
        }
        bridgeValueToRuntime(descriptor, normalizedValue, RuntimeParamSource.USER_SETTING);
    }

    /** Shows the settings dialog and returns whether the user confirmed with OK and the changes were applied. */
    public boolean showSettings(JFrame parentFrame) {
        return createDialog(parentFrame).showAndGet();
    }

    /** Saves to disk as soon as Apply or OK applies changes, so a killed process loses nothing. */
    SettingsDialog createDialog(JFrame parentFrame) {
        return new SettingsDialog(parentFrame, rootGroup, this::saveAppliedSettings);
    }

    private void saveAppliedSettings() {
        if (application == null || application.isDisposed()
                || !application.getServiceContainer().hasService(ComponentStore.class)) {
            return;
        }
        application.getService(ComponentStore.class).saveAll();
    }

    @Override
    public StateData getState() {
        StateData state = new StateData();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> state.values.add(new SettingValueState(entry.getKey(), entry.getValue())));
        return state;
    }

    @Override
    public void loadState(StateData state) {
        values.clear();
        if (state == null || state.values == null) {
            return;
        }
        for (SettingValueState value : state.values) {
            if (value.id != null && value.value != null) {
                values.put(value.id, value.value);
            }
        }
    }

    @Override
    public StateData getDefaultState() {
        return new StateData();
    }

    @Override
    public void dispose() {
        groups.clear();
        descriptors.clear();
        generatedPages.clear();
        values.clear();
    }

    private void bridgeDescriptorToRuntime(SettingDescriptor descriptor) {
        if (application == null || descriptor.getRuntimeBinding() == null) {
            return;
        }

        bridgeValueToRuntime(descriptor, getSettingValue(descriptor.getId()), RuntimeParamSource.STARTUP_CONFIG);
    }

    private void bridgeValueToRuntime(SettingDescriptor descriptor,
                                      Object value,
                                      RuntimeParamSource source) {
        if (application == null || descriptor.getRuntimeBinding() == null) {
            return;
        }

        var runtimeDescriptor = descriptor.getRuntimeBinding().getDescriptor();
        RuntimeParamRegistry.setStartupOrRuntimeValue(runtimeDescriptor, value, source);
    }

    private void registerBuiltInConfigurables() {
        if (application == null || !application.getServiceContainer().hasService(JdfLafManager.class)) {
            return;
        }
        registerConfigurable(
                APPEARANCE_GROUP_ID,
                APPEARANCE_GROUP_NAME,
                new ThemeSettingsConfigurable(application.getService(JdfLafManager.class)));
        registerConfigurable(APPEARANCE_GROUP_ID, APPEARANCE_GROUP_NAME, new LanguageSettingsConfigurable());
    }

    /** Persisted root object, which {@link com.jdesk.core.config.ComponentStore} reads and writes as XML. */
    public static final class StateData {
        public List<SettingValueState> values = new ArrayList<>();
    }

    /** Persisted value of one setting, keyed by {@link SettingDescriptor#getId()}. */
    public static final class SettingValueState {
        public String id;
        public String value;

        public SettingValueState() {
        }

        SettingValueState(String id, String value) {
            this.id = id;
            this.value = value;
        }
    }
}
