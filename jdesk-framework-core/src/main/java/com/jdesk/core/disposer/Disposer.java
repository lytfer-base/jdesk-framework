package com.jdesk.core.disposer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maintains the parent–child disposal tree of {@link Disposable} objects; the counterpart of IntelliJ Platform's
 * {@code com.intellij.openapi.util.Disposer}. {@link #register(Disposable, Disposable)} attaches a child to a parent,
 * and {@link #dispose(Disposable)} disposes every child before the parent itself and detaches it from the tree.
 * A child has one parent at a time, disposing twice is a no-op, and anything thrown by one object (including an
 * {@link Error}) is logged while the rest are still disposed.
 */
public final class Disposer {

    private static final Logger LOG = LoggerFactory.getLogger(Disposer.class);
    private static final Map<StrongIdentityReference, Node> nodeMap = new ConcurrentHashMap<>();
    private static final DisposedObjects disposedObjects = new DisposedObjects();

    private static final AtomicInteger anonymousCounter = new AtomicInteger();

    private Disposer() { }

    // ============== Factories ==============

    /** Creates an unnamed node, typically an intermediate group in the tree. */
    public static Disposable newDisposable() {
        Disposable d = new DisposableNode("anonymous-" + anonymousCounter.incrementAndGet());
        registerInternal(d);
        return d;
    }

    /** Creates a node whose {@code toString()} shows {@code debugName}, which helps trace leaks and disposal order. */
    public static Disposable newDisposable(String debugName) {
        Disposable d = new DisposableNode(debugName);
        registerInternal(d);
        return d;
    }

    /** Creates an unnamed node registered under {@code parent}. */
    public static Disposable newDisposable(Disposable parent) {
        Disposable child = newDisposable();
        register(parent, child);
        return child;
    }

    /** Creates a named node registered under {@code parent}. */
    public static Disposable newDisposable(Disposable parent, String debugName) {
        Disposable child = newDisposable(debugName);
        register(parent, child);
        return child;
    }

    // ============== Registration ==============

    /**
     * Registers {@code child} so that it is disposed before {@code parent}; a child under another parent moves here.
     *
     * @throws IllegalStateException if the parent or the child is already disposed
     */
    public static void register(Disposable parent, Disposable child) {
        if (parent == child) {
            throw new IllegalArgumentException("Cannot register a disposable as its own parent");
        }
        if (isDisposed(parent)) {
            throw new IllegalStateException("Parent is already disposed: " + parent);
        }
        if (isDisposed(child)) {
            throw new IllegalStateException("Child is already disposed: " + child);
        }

        Node parentNode = nodeMap.computeIfAbsent(new StrongIdentityReference(parent), key -> new Node(parent));
        if (parentNode.disposed) {
            throw new IllegalStateException("Parent is already disposed: " + parent);
        }

        Node childNode = nodeMap.computeIfAbsent(new StrongIdentityReference(child), key -> new Node(child));

        // Serialize registrations of one child; re-check after attaching and roll back if either side was disposed meanwhile.
        synchronized (childNode) {
            Node oldParent = childNode.parent;
            if (oldParent != null && oldParent != parentNode) {
                removeChild(oldParent, child);
            }

            childNode.parent = parentNode;
            if (!containsChild(parentNode, child)) {
                parentNode.children.add(child);
            }

            if (isDisposed(parent) || isDisposed(child)) {
                removeChild(parentNode, child);
                if (childNode.parent == parentNode) {
                    childNode.parent = null;
                }
                throw new IllegalStateException(
                        "Parent or child was disposed concurrently during registration: " + parent);
            }
        }
    }

    /** Like {@link #register(Disposable, Disposable)} but returns {@code false} instead of throwing, for shutdown paths. */
    public static boolean tryRegister(Disposable parent, Disposable child) {
        if (isDisposed(parent)) {
            return false;
        }
        try {
            register(parent, child);
            return true;
        } catch (Exception e) {
            LOG.debug("Failed to register disposable: {}", e.getMessage());
            return false;
        }
    }

    // ============== Disposal ==============

    /**
     * Disposes {@code disposable} and all its registered children, children first; calling it again is a no-op.
     * An object that was never registered is disposed directly as well.
     */
    public static void dispose(Disposable disposable) {
        Node node = nodeMap.get(new StrongIdentityReference(disposable));
        if (node == null) {
            if (isDisposed(disposable)) {
                return;
            }
            disposedObjects.add(disposable);
            try {
                disposable.dispose();
            } catch (Throwable e) {
                LOG.error("Error disposing unregistered disposable: {}", disposable, e);
            }
            return;
        }
        if (node.disposed) return;

        // Mark first so that re-entrant calls return early.
        node.disposed = true;
        disposedObjects.add(disposable);

        List<Disposable> snapshot = new ArrayList<>(node.children);
        for (Disposable child : snapshot) {
            try {
                dispose(child);
            } catch (Throwable e) {
                LOG.error("Error disposing child: {}", child, e);
            }
        }
        node.children.clear();

        try {
            disposable.dispose();
        } catch (Throwable e) {
            LOG.error("Error disposing: {}", disposable, e);
        }

        removeFromActiveTree(disposable, node);
    }

    /** Returns whether the object has been disposed; this stays answerable for a while after it leaves the tree. */
    public static boolean isDisposed(Disposable disposable) {
        Node node = nodeMap.get(new StrongIdentityReference(disposable));
        return (node != null && node.disposed) || disposedObjects.contains(disposable);
    }

    /** Returns whether the object is being disposed through the active tree right now. */
    public static boolean isDisposing(Disposable disposable) {
        Node node = nodeMap.get(new StrongIdentityReference(disposable));
        return node != null && node.disposed;
    }

    /**
     * Removes the node from the active tree without calling {@link Disposable#dispose()}; use it only for an object
     * that has already cleaned up and has no children left.
     *
     * @throws IllegalStateException if the node still has children
     */
    public static void removeNode(Disposable disposable) {
        Node node = nodeMap.get(new StrongIdentityReference(disposable));
        if (node != null) {
            if (!node.children.isEmpty()) {
                throw new IllegalStateException(
                        "Cannot remove disposable node with undisposed children: " + disposable);
            }
            node.disposed = true;
            disposedObjects.add(disposable);
            removeFromActiveTree(disposable, node);
        }
    }

    // ============== Diagnostics ==============

    /** Returns how many disposables the active tree tracks; tests use it to check for leaks. */
    public static int getTreeSize() {
        return nodeMap.size();
    }

    /** Clears all tracking state; tests only. */
    public static void clearAll() {
        nodeMap.clear();
        disposedObjects.clear();
        anonymousCounter.set(0);
    }

    /** Counts the parents whose child list contains {@code child}; more than one means a registration race. Tests only. */
    static int countParentsOf(Disposable child) {
        int count = 0;
        for (Node node : nodeMap.values()) {
            for (Disposable existing : node.children) {
                if (existing == child) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    // ============== Internals ==============

    private static void registerInternal(Disposable d) {
        nodeMap.putIfAbsent(new StrongIdentityReference(d), new Node(d));
    }

    private static void removeFromActiveTree(Disposable disposable, Node node) {
        // Must hold the same lock as register, so a concurrent registration never sees a half-removed node.
        synchronized (node) {
            Node parentNode = node.parent;
            if (parentNode != null) {
                removeChild(parentNode, disposable);
            }
            node.parent = null;
            node.children.clear();
            nodeMap.remove(new StrongIdentityReference(disposable), node);
        }
    }

    private static boolean containsChild(Node parentNode, Disposable child) {
        for (Disposable existing : parentNode.children) {
            if (existing == child) {
                return true;
            }
        }
        return false;
    }

    private static void removeChild(Node parentNode, Disposable child) {
        parentNode.children.removeIf(existing -> existing == child);
    }

    private static class Node {
        final Disposable owner;
        Node parent;
        final List<Disposable> children = new CopyOnWriteArrayList<>();
        volatile boolean disposed;

        Node(Disposable owner) {
            this.owner = owner;
        }
    }

    private static class DisposableNode implements Disposable {
        private final String debugName;

        DisposableNode(String debugName) {
            this.debugName = debugName;
        }

        @Override
        public void dispose() {
            // Only a tree node; nothing to release.
        }

        @Override
        public String toString() {
            return "Disposable{" + debugName + "}";
        }
    }

    private static final class DisposedObjects {
        private final ReferenceQueue<Disposable> referenceQueue = new ReferenceQueue<>();
        private final Set<WeakIdentityReference> references = ConcurrentHashMap.newKeySet();

        void add(Disposable disposable) {
            expungeStaleReferences();
            references.add(new WeakIdentityReference(disposable, referenceQueue));
        }

        boolean contains(Disposable disposable) {
            expungeStaleReferences();
            return references.contains(new StrongIdentityReference(disposable));
        }

        void clear() {
            references.clear();
            while (referenceQueue.poll() != null) {
                // Drain the queue.
            }
        }

        private void expungeStaleReferences() {
            Reference<? extends Disposable> reference;
            while ((reference = referenceQueue.poll()) != null) {
                references.remove(reference);
            }
        }
    }

    private static final class WeakIdentityReference extends WeakReference<Disposable> {
        private final int hashCode;

        WeakIdentityReference(Disposable disposable, ReferenceQueue<Disposable> referenceQueue) {
            super(disposable, referenceQueue);
            this.hashCode = System.identityHashCode(disposable);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            Disposable disposable = get();
            if (disposable == null) {
                return false;
            }
            if (obj instanceof WeakIdentityReference other) {
                return disposable == other.get();
            }
            if (obj instanceof StrongIdentityReference other) {
                return disposable == other.disposable;
            }
            return false;
        }
    }

    private static final class StrongIdentityReference {
        private final Disposable disposable;
        private final int hashCode;

        StrongIdentityReference(Disposable disposable) {
            this.disposable = disposable;
            this.hashCode = System.identityHashCode(disposable);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof StrongIdentityReference other) {
                return disposable == other.disposable;
            }
            if (obj instanceof WeakIdentityReference other) {
                return disposable == other.get();
            }
            return false;
        }
    }
}
