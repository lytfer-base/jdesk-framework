package com.jdesk.core.vfs;

import java.util.Objects;

/**
 * An event describing a virtual file system change; it carries the change type and the files involved and performs
 * no file operation.
 */
public class VfsEvent {

    public enum Type {
        FILE_CREATED,
        FILE_DELETED,
        FILE_MOVED,
        FILE_CHANGED,       // The content changed.
        FILE_ATTRIBUTES_CHANGED,
        BEFORE_FILE_DELETED,
        BEFORE_FILE_MOVED
    }

    private final Type type;
    private final VirtualFile file;
    private final VirtualFile oldParent;
    private final VirtualFile newParent;
    private final String oldName;
    private final String newName;

    private VfsEvent(Type type, VirtualFile file,
                     VirtualFile oldParent, VirtualFile newParent,
                     String oldName, String newName) {
        this.type = type;
        this.file = file;
        this.oldParent = oldParent;
        this.newParent = newParent;
        this.oldName = oldName;
        this.newName = newName;
    }

    // ---- Factories ----

    public static VfsEvent created(VirtualFile file) {
        return new VfsEvent(Type.FILE_CREATED, file, null, null, null, null);
    }

    public static VfsEvent deleted(VirtualFile file) {
        return new VfsEvent(Type.FILE_DELETED, file, null, null, null, null);
    }

    public static VfsEvent beforeDeleted(VirtualFile file) {
        return new VfsEvent(Type.BEFORE_FILE_DELETED, file, null, null, null, null);
    }

    public static VfsEvent changed(VirtualFile file) {
        return new VfsEvent(Type.FILE_CHANGED, file, null, null, null, null);
    }

    public static VfsEvent moved(VirtualFile file, VirtualFile oldParent, VirtualFile newParent) {
        return new VfsEvent(Type.FILE_MOVED, file, oldParent, newParent, file.getName(), file.getName());
    }

    public static VfsEvent renamed(VirtualFile file, String oldName) {
        return new VfsEvent(Type.FILE_MOVED, file, file.getParent(), file.getParent(), oldName, file.getName());
    }

    public static VfsEvent attributesChanged(VirtualFile file) {
        return new VfsEvent(Type.FILE_ATTRIBUTES_CHANGED, file, null, null, null, null);
    }

    // ---- Accessors ----

    public Type getType() { return type; }
    public VirtualFile getFile() { return file; }
    public VirtualFile getOldParent() { return oldParent; }
    public VirtualFile getNewParent() { return newParent; }
    public String getOldName() { return oldName; }
    public String getNewName() { return newName; }

    @Override
    public String toString() {
        return "VfsEvent{" + type + " " + file + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VfsEvent that)) return false;
        return type == that.type && Objects.equals(file, that.file);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, file);
    }
}
