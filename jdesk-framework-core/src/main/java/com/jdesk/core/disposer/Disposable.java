package com.jdesk.core.disposer;

/**
 * An object that releases resources when its lifecycle ends; the counterpart of IntelliJ Platform's
 * {@code com.intellij.openapi.Disposable}. {@link Disposer} arranges these objects in a parent–child tree, and
 * disposing a parent disposes its children first.
 * Don't implement it with a lambda: use a named class or {@link Disposer#newDisposable(String)} so leaks stay traceable.
 */
public interface Disposable {

    /** Releases the resources this object holds; release it through {@link Disposer#dispose(Disposable)}, not directly. */
    void dispose();

    /** A {@code Disposable} with an empty {@code dispose()}, for objects that only act as lifecycle parents. */
    interface Default extends Disposable {
        @Override
        default void dispose() { }
    }
}
