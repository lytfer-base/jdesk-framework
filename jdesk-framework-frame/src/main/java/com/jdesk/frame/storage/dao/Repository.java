package com.jdesk.frame.storage.dao;

/**
 * Base repository contract for modules that expose entity-oriented storage.
 *
 * <p>Domain repositories may extend this interface for simple CRUD cases, or
 * keep their own business-shaped methods while delegating to {@link EntityDao}.
 * The interface is deliberately small in the first version; richer repository
 * helpers can be added after real module usage stabilizes.
 */
public interface Repository<T, ID> extends EntityDao<T, ID> {
}
