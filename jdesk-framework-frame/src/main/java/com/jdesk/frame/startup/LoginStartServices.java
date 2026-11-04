package com.jdesk.frame.startup;

import com.jdesk.core.app.Application;
import com.jdesk.core.disposer.Disposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds the optional {@link LoginStartService} through {@link ServiceLoader}: providers are listed in
 * {@code META-INF/services/com.jdesk.frame.startup.LoginStartServiceProvider}, and a build without one has none.
 * The first provider that gives a service wins; a provider that fails is logged and skipped.
 */
public final class LoginStartServices {

    private static final Logger LOG = LoggerFactory.getLogger(LoginStartServices.class);
    // Don't retry without a limit: an unreadable provider configuration fails again on every iterator call.
    private static final int MAX_LOOKUP_FAILURES = 8;

    private LoginStartServices() {
    }

    /**
     * Looks the service up through the application's class loader, or this class's when there is no application.
     * The caller owns the returned service: register it under an owner with {@link Disposer#register}.
     */
    public static Optional<LoginStartService> create(Application application) {
        ClassLoader loader = application == null ? null : application.getApplicationClassLoader();
        return create(application, loader != null ? loader : LoginStartServices.class.getClassLoader());
    }

    static Optional<LoginStartService> create(Application application, ClassLoader loader) {
        Iterator<LoginStartServiceProvider> providers = ServiceLoader.load(LoginStartServiceProvider.class, loader).iterator();
        int lookupFailures = 0;
        while (lookupFailures < MAX_LOOKUP_FAILURES) {
            LoginStartServiceProvider provider;
            try {
                if (!providers.hasNext()) {
                    break;
                }
                provider = providers.next();
            } catch (ServiceConfigurationError | LinkageError failure) {
                lookupFailures++;
                LOG.warn("loginStart.service.provider.unavailable", failure);
                continue;
            }
            Optional<LoginStartService> service = serviceOf(provider, application);
            if (service.isPresent()) {
                LOG.info("loginStart.service.selected provider={}", provider.getClass().getName());
                return service;
            }
        }
        LOG.info("loginStart.service.none");
        return Optional.empty();
    }

    private static Optional<LoginStartService> serviceOf(LoginStartServiceProvider provider, Application application) {
        try {
            return Objects.requireNonNull(provider.create(application), "provider returned null instead of an Optional");
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn("loginStart.service.provider.failed provider={}", provider.getClass().getName(), failure);
            return Optional.empty();
        }
    }
}
