package com.jdesk.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A shared path access policy for storage and file operations, optionally bound to an existing root.
 * This policy does not lock directories against external moves or replacements.
 */
public final class PathAccessPolicy {
    private static final PathAccessPolicy CREATE_MISSING = new PathAccessPolicy(null);
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private final Path root;

    private PathAccessPolicy(Path root) {
        this.root = root;
    }

    /** Returns the default policy that permits creating missing paths. */
    public static PathAccessPolicy createMissing() {
        return CREATE_MISSING;
    }

    /** Returns a policy that requires the given absolute root to remain available without recreating it. */
    public static PathAccessPolicy existingRoot(Path root) {
        Objects.requireNonNull(root, "root");
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("Existing root must be absolute");
        }
        return new PathAccessPolicy(root.normalize());
    }

    /** Returns whether operations must stay within an existing root. */
    public boolean requiresExistingRoot() {
        return root != null;
    }

    /** Returns the normalized root, or null for the default creation policy. */
    public Path root() {
        return root;
    }

    /** Returns the policy of the current operation, or the default policy outside a scope. */
    public static PathAccessPolicy current() {
        Scope scope = ACTIVE.get();
        return scope == null ? CREATE_MISSING : scope.policy;
    }

    /** Captures this policy combined with the current operation; nested policies can restrict but never relax it. */
    public PathAccessPolicy capture() {
        PathAccessPolicy active = current();
        if (root == null) return active;
        if (active.root == null || root.startsWith(active.root)) return this;
        if (active.root.startsWith(root)) return active;
        throw new IllegalStateException("Incompatible existing roots in one operation");
    }

    /** Enters an operation scope; close it on this thread in reverse order, and capture it before queueing work. */
    public Scope enter() {
        Scope scope = new Scope(capture(), ACTIVE.get());
        ACTIVE.set(scope);
        return scope;
    }

    /** A thread-bound operation scope that restores the previous path policy on close. */
    public static final class Scope implements AutoCloseable {
        private final PathAccessPolicy policy;
        private final Scope previous;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private Scope(PathAccessPolicy policy, Scope previous) {
            this.policy = policy;
            this.previous = previous;
        }

        /** Restores the preceding policy; rejects closing from another thread or out of order. */
        @Override
        public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Path scope belongs to another thread");
            if (closed) return;
            if (ACTIVE.get() != this) throw new IllegalStateException("Path scopes must close in reverse order");
            closed = true;
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

    /** Checks the target boundary and root availability without creating paths; throws when access is unavailable. */
    public void checkAccess(Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        if (root != null) {
            if (!target.toAbsolutePath().normalize().startsWith(root)) {
                throw new IOException("Path is outside the required root: " + target);
            }
            if (!Files.isDirectory(root)) {
                throw new IOException("Required root is unavailable: " + root);
            }
        }
    }

    /** Creates missing directories under this policy; strict scopes never recreate an observed existing ancestor. */
    public void createDirectories(Path directory) throws IOException {
        PathAccessPolicy access = capture();
        if (!access.requiresExistingRoot()) {
            Files.createDirectories(directory);
            return;
        }
        access.createDirectories(directory, created -> { });
    }

    /** Creates directories and reports each directory created by this call, in creation order. */
    public void createDirectories(Path directory, Consumer<Path> created) throws IOException {
        Objects.requireNonNull(created, "created");
        PathAccessPolicy access = capture();
        Path target = directory.toAbsolutePath().normalize();
        access.checkAccess(target);
        var missing = new ArrayList<Path>();
        Path anchor = target;
        for (;;) {
            try {
                if (!Files.readAttributes(anchor, BasicFileAttributes.class).isDirectory()) {
                    throw new IOException("Required parent is not a directory: " + anchor);
                }
                break;
            } catch (NoSuchFileException absent) {
                if (anchor.equals(access.root) || anchor.getParent() == null) throw absent;
                missing.add(anchor);
                anchor = anchor.getParent();
            }
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            access.checkAccess(target);
            Path child = missing.get(i);
            try {
                Files.createDirectory(child);
                created.accept(child);
            } catch (FileAlreadyExistsException concurrent) {
                if (!Files.isDirectory(child)) throw concurrent;
            }
        }
        access.checkAccess(target);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PathAccessPolicy policy && Objects.equals(root, policy.root);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(root);
    }

    @Override
    public String toString() {
        return root == null ? "CREATE_MISSING" : "EXISTING_ROOT(" + root + ")";
    }
}
