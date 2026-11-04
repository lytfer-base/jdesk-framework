package com.jdesk.frame.startup;

import com.jdesk.core.disposer.Disposable;

import java.io.IOException;

/**
 * The optional setting that starts the application when the user signs in, found through
 * {@link LoginStartServices}. Both methods block: don't call them on the EDT.
 */
public interface LoginStartService extends Disposable {

    /** Tells whether the application starts when the user signs in; blocks. */
    boolean isEnabled() throws IOException;

    /** Turns starting at sign-in on or off and returns the state read back afterwards; blocks. */
    boolean setEnabled(boolean enabled) throws IOException;
}
