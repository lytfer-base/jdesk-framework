package com.jdesk.core.config;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Physical storage of one component's state; implementations serialize the state to their medium and skip writes
 * when the content hasn't changed.
 */
public interface StateStorage {

    Path getPath();

    boolean exists();

    <T> T loadState(Class<T> stateClass) throws IOException;

    /** Saves the state and returns {@code true} if the content changed and was written. */
    boolean saveState(Object state) throws IOException;

    /**
     * Moves corrupted content aside, keeping a copy for manual recovery; called after {@link #loadState} throws and
     * before the component falls back to its defaults. Best effort and must not throw; {@code false} if nothing was
     * backed up.
     */
    default boolean backupCorrupted() {
        return false;
    }
}
