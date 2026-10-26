package com.jdesk.frame.update;

import com.jdesk.core.app.Application;

import java.util.Optional;

/**
 * Service-provider interface of the optional update service. A jar that can update the application lists its
 * implementation in {@code META-INF/services/com.jdesk.frame.update.UpdateServiceProvider}.
 */
public interface UpdateServiceProvider {

    /**
     * Returns the update service when this build can update itself, otherwise empty.
     * Must return quickly and start no work: the service does its work from {@link UpdateService#start}.
     */
    Optional<UpdateService> create(Application application);
}
