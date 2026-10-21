package com.jdesk.frame.storage.dao;

import java.util.Optional;

/**
 * Minimal entity-oriented DAO generated from frame storage metadata.
 *
 * <p>This interface intentionally contains only the CRUD operations that can be
 * generated safely from one annotated entity. Business-shaped queries should
 * live in module repositories and delegate to this DAO or to
 * {@code DatabaseSession} SQL methods.
 */
public interface EntityDao<T, ID> {

    /**
     * Finds one entity by its {@code @Id} column.
     */
    Optional<T> findById(ID id);

    /**
     * Inserts a complete entity row and returns the entity id value.
     */
    ID insert(T entity);

    /**
     * Updates all non-id columns for the entity row identified by its id.
     */
    void update(T entity);

    /**
     * Deletes one entity row by id.
     */
    void deleteById(ID id);
}
