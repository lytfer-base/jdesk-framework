package com.jdesk.frame.update;

import java.nio.file.Path;
import java.util.List;

/** What the host application offers to an {@link UpdateService}: a command to run after the application exits. */
public interface UpdateHost {

    /**
     * Registers {@code program}, an absolute path, to run with {@code arguments} once the application has exited;
     * {@code exitNow} also asks the application to exit right away.
     */
    void runAfterExit(Path program, List<String> arguments, boolean exitNow);

    /** Withdraws the command registered with {@link #runAfterExit}; does nothing when there is none. */
    void clearRunAfterExit();
}
