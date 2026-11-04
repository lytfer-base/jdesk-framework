package com.jdesk.frame.startup;

import com.jdesk.core.app.Application;

import java.util.Optional;

/**
 * Service-provider interface of the optional login-start service. A jar that can start the application at sign-in
 * lists its implementation in {@code META-INF/services/com.jdesk.frame.startup.LoginStartServiceProvider}.
 */
public interface LoginStartServiceProvider {

    /**
     * Returns the login-start service when this build supports it, otherwise empty.
     * Must return quickly and do no work: the service does its work from its own methods.
     */
    Optional<LoginStartService> create(Application application);
}
