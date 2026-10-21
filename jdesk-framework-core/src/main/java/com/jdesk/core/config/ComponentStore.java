package com.jdesk.core.config;

import com.jdesk.core.service.ServiceContainer;
import com.jdesk.core.startup.StartupSpan;
import com.jdesk.core.startup.StartupTrace;
import com.jdesk.core.startup.StartupTraces;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-level store of component state, modeled on IntelliJ Platform's ComponentStoreImpl. It registers
 * {@link PersistentStateComponent}s declared with {@link State}, loads their state from XML files through
 * {@link FileStateStorage} on registration and saves it with {@link #saveAll()}; deprecated storages are only read.
 * Saves are serialized, so concurrent {@link #saveAll()} calls never write older state over newer; {@code getState()}
 * may run on any thread, so components must hand out a snapshot or synchronize.
 */
public class ComponentStore {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentStore.class);

    private final Path configDir;

    private final StateStorageManager storageManager;

    /** Registered components by name, usually {@link State#name()}. */
    private final Map<String, ComponentInfo<?>> components = new ConcurrentHashMap<>();

    /** Serializes taking, serializing and writing the state. */
    private final Object saveLock = new Object();

    /** Creates a store over {@code configDir}, creating the directory if it is missing. */
    public ComponentStore(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.storageManager = new DefaultStateStorageManager(configDir, stateMapper());
        try {
            Files.createDirectories(configDir);
        }
        catch (IOException e) {
            LOG.error("Failed to create config directory: {}", configDir, e);
        }
    }

    /**
     * Keep {@code NON_NULL}: a null field would be written as an empty element that reads back as {@code ""}, so "never
     * saved" and "saved empty" would look the same. Don't read empty elements as null either; that breaks fields that
     * use the empty string as a sentinel.
     */
    private static XmlMapper stateMapper() {
        return XmlMapper.builder()
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    /** Registers a component annotated with {@link State} and loads its saved state, or its default state if none. */
    public void register(PersistentStateComponent<?> component) {
        Objects.requireNonNull(component, "component");
        State state = findStateAnnotation(component.getClass());
        if (state == null) {
            throw new IllegalArgumentException(
                    "No @State annotation found on " + component.getClass().getName());
        }
        registerAnnotated(component, state);
    }

    /** Registers a component with explicit metadata instead of {@link State}, for tests and simple applications. */
    public <T> void register(PersistentStateComponent<T> component,
                             Class<T> stateClass,
                             String fileName) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(stateClass, "stateClass");
        Objects.requireNonNull(fileName, "fileName");

        String componentName = stateClass.getName();
        ComponentInfo<T> info = new ComponentInfo<>(
                componentName,
                component,
                stateClass,
                List.of(new ComponentInfo.StorageSpec(fileName, false)));
        registerInfo(info);
    }

    /** Registers the created services of {@code serviceContainer} that are persistent components with {@link State}. */
    public void registerExistingServices(ServiceContainer serviceContainer) {
        Objects.requireNonNull(serviceContainer, "serviceContainer");
        var services = serviceContainer.getCreatedServicesSnapshot();
        for (Object service : services) {
            registerServiceIfPersistent(service);
        }
        StartupTraces.getCurrent().addCounter("componentStore.existingServices.scanned", services.size());
    }

    /** Registers {@code service} if it is a {@link PersistentStateComponent} annotated with {@link State}. */
    public void registerServiceIfPersistent(Object service) {
        if (!(service instanceof PersistentStateComponent<?> component)) {
            return;
        }
        if (findStateAnnotation(service.getClass()) == null) {
            return;
        }
        register(component);
    }

    /**
     * Saves the component's state once and unregisters it, so its name can be registered again; an unregistered
     * component is ignored.
     */
    public void unregister(PersistentStateComponent<?> component) {
        Objects.requireNonNull(component, "component");
        for (ComponentInfo<?> info : components.values()) {
            if (info.component() == component) {
                saveComponent(info);
                components.remove(info.name(), info);
                return;
            }
        }
    }

    /** Saves the current state of every registered component, in name order. */
    public void saveAll() {
        components.values().stream()
                .sorted(Comparator.comparing(ComponentInfo::name))
                .forEach(this::saveComponent);
    }

    /**
     * Strictly saves {@code candidate} for a registered component with one writable storage, then loads it under the
     * same save lock; a failed write leaves memory unchanged. Validate the candidate first, and keep {@code loadState}
     * to memory updates: it must not throw, wait for the UI or publish events.
     */
    public <T> void saveState(PersistentStateComponent<T> component, T candidate) throws IOException {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(candidate, "candidate");
        synchronized (saveLock) {
            ComponentInfo<?> info = components.values().stream()
                    .filter(registered -> registered.component() == component)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Component is not registered"));
            if (!info.stateClass().isInstance(candidate)) {
                throw new IllegalArgumentException("Candidate does not match the registered state type");
            }
            List<ComponentInfo.StorageSpec> writable = info.storages().stream()
                    .filter(storage -> !storage.deprecated())
                    .toList();
            if (writable.size() != 1) {
                throw new IllegalArgumentException("Strict state saving requires exactly one writable storage");
            }
            storageManager.getStateStorage(writable.get(0).path()).saveState(candidate);
            component.loadState(candidate);
        }
    }

    /** Returns the configuration directory. */
    public Path getConfigDir() {
        return configDir;
    }

    /** Returns the state storage manager. */
    public StateStorageManager getStorageManager() {
        return storageManager;
    }

    /** Returns the {@link State} annotation found along the class hierarchy, or {@code null}. */
    public static State findStateAnnotation(Class<?> componentClass) {
        Class<?> current = componentClass;
        while (current != null && current != Object.class) {
            State state = current.getAnnotation(State.class);
            if (state != null) {
                return state;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerAnnotated(PersistentStateComponent<?> component, State state) {
        Class<?> stateClass = resolveStateClass(component.getClass());
        ComponentInfo info = new ComponentInfo(
                state.name(),
                component,
                stateClass,
                storageSpecs(state));
        registerInfo(info);
    }

    /** Defaults to {@code <name>.xml} when no storage, or a storage with a blank path, is declared. */
    private List<ComponentInfo.StorageSpec> storageSpecs(State state) {
        Storage[] storages = state.storages();
        if (storages.length == 0) {
            return List.of(new ComponentInfo.StorageSpec(state.name() + ".xml", false));
        }

        List<ComponentInfo.StorageSpec> result = new ArrayList<>(storages.length);
        for (Storage storage : storages) {
            String path = storage.value().isBlank() ? state.name() + ".xml" : storage.value();
            result.add(new ComponentInfo.StorageSpec(path, storage.deprecated()));
        }
        return result;
    }

    /** Ignores the same instance registered twice; another instance with the same name throws IllegalStateException. */
    private <T> void registerInfo(ComponentInfo<T> info) {
        ComponentInfo<?> existing = components.putIfAbsent(info.name(), info);
        if (existing != null) {
            if (existing.component() == info.component()) {
                return;
            }
            throw new IllegalStateException("Conflicting persistent component name: " + info.name());
        }
        loadComponent(info);
    }

    /**
     * Loads the state from the first readable storage, current ones before deprecated ones, backing up each corrupted
     * file before trying the next; without any, falls back to {@code getDefaultState()} or {@code noStateLoaded()}.
     */
    private <T> void loadComponent(ComponentInfo<T> info) {
        StartupTrace startupTrace = StartupTraces.getCurrent();
        try (StartupSpan span = startupTrace.begin("componentState.load:" + info.name())) {
            span.detail("component", info.name());
            for (ComponentInfo.StorageSpec storageSpec : storagesForRead(info)) {
                StateStorage storage = storageManager.getStateStorage(storageSpec.path());
                span.detail("storage", storage.getPath());
                if (!storage.exists()) {
                    continue;
                }
                try {
                    T state = storage.loadState(info.stateClass());
                    info.component().loadState(state);
                    startupTrace.incrementCounter("componentStates.loaded");
                    LOG.info("Loaded component state '{}' from {}", info.name(), storage.getPath());
                    return;
                }
                catch (Exception e) {
                    startupTrace.incrementCounter("componentStates.loadErrors");
                    LOG.warn("Failed to load component state '{}' from {}: {}",
                            info.name(), storage.getPath(), e.getMessage());
                    // Back up the corrupted file, or the next saveAll overwrites it with the defaults.
                    if (storage.backupCorrupted()) {
                        startupTrace.incrementCounter("componentStates.corruptBackups");
                        LOG.warn("Backed up corrupted state file of '{}' to {}.corrupt", info.name(), storage.getPath());
                    }
                }
            }

            T defaultState = info.component().getDefaultState();
            if (defaultState != null) {
                info.component().loadState(defaultState);
                startupTrace.incrementCounter("componentStates.defaultLoaded");
            }
            else {
                info.component().noStateLoaded();
                startupTrace.incrementCounter("componentStates.empty");
            }
        }
    }

    /** Current storages first; don't reverse the order. */
    private List<ComponentInfo.StorageSpec> storagesForRead(ComponentInfo<?> info) {
        return info.storages().stream()
                .sorted(Comparator.comparing(ComponentInfo.StorageSpec::deprecated))
                .toList();
    }

    /**
     * Saves one component to each current storage, skipping a {@code null} state; a failing storage or component is
     * logged and doesn't stop the others.
     */
    private <T> void saveComponent(ComponentInfo<T> info) {
        synchronized (saveLock) {
            try {
                T state = info.component().getState();
                if (state == null) {
                    return;
                }

                for (ComponentInfo.StorageSpec storageSpec : info.storages()) {
                    if (storageSpec.deprecated()) {
                        continue;
                    }
                    StateStorage storage = storageManager.getStateStorage(storageSpec.path());
                    try {
                        if (storage.saveState(state)) {
                            LOG.debug("Saved component state '{}' to {}", info.name(), storage.getPath());
                        }
                    }
                    catch (Exception e) {
                        LOG.error("Failed to save component state '{}' to {}", info.name(), storage.getPath(), e);
                    }
                }
            }
            catch (Exception e) {
                LOG.error("Failed to save component '{}', skipping", info.name(), e);
            }
        }
    }

    /**
     * Infers {@code T} of {@code PersistentStateComponent<T>} along the class hierarchy and its interfaces, taking the
     * raw class of a generic {@code T}; throws IllegalArgumentException if it can't.
     */
    private static Class<?> resolveStateClass(Class<?> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass");
        Class<?> current = componentClass;
        while (current != null && current != Object.class) {
            Class<?> fromInterfaces = resolveStateClassFromInterfaces(current.getGenericInterfaces());
            if (fromInterfaces != null) {
                return fromInterfaces;
            }

            Type genericSuperclass = current.getGenericSuperclass();
            Class<?> fromType = resolveStateClassFromType(genericSuperclass);
            if (fromType != null) {
                return fromType;
            }
            current = current.getSuperclass();
        }

        throw new IllegalArgumentException(
                "Cannot infer PersistentStateComponent state class for " + componentClass.getName()
                        + ". Use register(component, stateClass, fileName).");
    }

    private static Class<?> resolveStateClassFromInterfaces(Type[] interfaces) {
        for (Type type : interfaces) {
            Class<?> stateClass = resolveStateClassFromType(type);
            if (stateClass != null) {
                return stateClass;
            }
            if (type instanceof Class<?> interfaceClass) {
                stateClass = resolveStateClassFromInterfaces(interfaceClass.getGenericInterfaces());
                if (stateClass != null) {
                    return stateClass;
                }
            }
        }
        return null;
    }

    private static Class<?> resolveStateClassFromType(Type type) {
        if (!(type instanceof ParameterizedType parameterizedType)) {
            return null;
        }

        Type rawType = parameterizedType.getRawType();
        if (rawType == PersistentStateComponent.class) {
            Type stateType = parameterizedType.getActualTypeArguments()[0];
            if (stateType instanceof Class<?> stateClass) {
                return stateClass;
            }
            if (stateType instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> stateClass) {
                return stateClass;
            }
        }

        if (rawType instanceof Class<?> rawClass) {
            return resolveStateClassFromInterfaces(rawClass.getGenericInterfaces());
        }
        return null;
    }
}
