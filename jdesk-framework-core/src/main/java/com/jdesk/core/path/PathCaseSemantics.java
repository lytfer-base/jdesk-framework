package com.jdesk.core.path;

/**
 * Path case semantics of a file system: decides in one place whether two path strings name the same entry. Use it
 * only to compare and build keys (storing a canonical key as an index is fine); wherever the string acts as a path, in
 * the UI, {@code Files.*}, creating or renaming, use the original. It folds case only; separators, slashes, dot
 * segments and Unicode normalization are up to the caller. Case sensitivity is a property of the volume, not the
 * platform: pick a second implementation per volume, not per operating system.
 */
public interface PathCaseSemantics {

    /**
     * Returns the implementation for this process, always the Windows one. Supporting another kind of volume also
     * means changing the call shape, for example to a factory that takes the path: this method can't see the volume.
     */
    static PathCaseSemantics current() {
        return WindowsPathCaseSemantics.INSTANCE;
    }

    /**
     * Returns the canonical form for comparing and keys; never use it as a path. Pass both sides of a comparison
     * through it, and expect different disk names to collide in one key (differentDiskNamesCanCollideIntoOneKey). Its
     * length may differ from the original, so don't cut the original with indexes taken from the canonical form.
     *
     * @throws NullPointerException if {@code path} is null
     */
    String canonicalKey(String path);

    /**
     * Returns whether two path strings name the same entry, ignoring case only: different separators or a trailing
     * slash still make them differ. Like {@link #canonicalKey}, it may disagree with the file system's own judgment.
     *
     * @throws NullPointerException if either argument is null
     */
    default boolean sameName(String left, String right) {
        return canonicalKey(left).equals(canonicalKey(right));
    }
}
