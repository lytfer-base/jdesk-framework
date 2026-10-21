package com.jdesk.core.config;

import java.nio.file.Path;

/**
 * Resolves storage declarations to {@link StateStorage}s and file system paths; implementations expand and collapse
 * path macros and cache one storage per path.
 */
public interface StateStorageManager {

    StateStorage getStateStorage(String pathSpec);

    Path expandMacro(String pathSpec);

    String collapseMacro(Path path);

    Path getConfigDir();
}
