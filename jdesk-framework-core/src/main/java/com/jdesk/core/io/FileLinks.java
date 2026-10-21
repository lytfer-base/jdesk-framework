package com.jdesk.core.io;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Tells whether an entry met during a walk is a link: a symbolic link, or a directory that is a name-surrogate
 * reparse point such as a junction or a volume mount point. Everything else counts as a regular file or folder.
 * Don't rely on {@code attrs.isOther()} alone: reparse points that aren't links are "other" too.
 */
public final class FileLinks {

    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
    /** IsReparseTagNameSurrogate: set in the reparse tags of junctions, volume mount points and symbolic links, WSL ones included. */
    private static final int NAME_SURROGATE_BIT = 0x20000000;
    private static final boolean WINDOWS = Platform.isWindows();

    private FileLinks() {
    }

    /** {@code attrs} must be read without following links, as {@code walkFileTree} supplies them; an unreadable reparse tag means not a link. */
    public static boolean isLink(Path entry, BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink()) {
            return true;
        }
        return WINDOWS && attrs.isDirectory() && attrs.isOther() && isNameSurrogate(entry);
    }

    /** Reads the attributes without following links and decides from them; an unreadable entry is not a link. */
    public static boolean isLink(Path entry) {
        try {
            return isLink(entry, Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException e) {
            return false;
        }
    }

    /** Returns whether {@code dir} or any level between it and {@code root} is a link; {@code dir} must be under {@code root}, and equal paths give false. */
    public static boolean reachedThroughLink(Path root, Path dir) {
        if (dir.equals(root)) {
            return false;
        }
        Path current = root;
        for (Path name : root.relativize(dir)) {
            current = current.resolve(name);
            if (isLink(current)) {
                return true;
            }
        }
        return false;
    }

    /** Returns false when the entry can't be queried, for example because it just vanished or its parent can't be listed. */
    private static boolean isNameSurrogate(Path entry) {
        try {
            WinBase.WIN32_FIND_DATA data = new WinBase.WIN32_FIND_DATA();
            WinNT.HANDLE handle = Kernel32.INSTANCE.FindFirstFile(win32Path(entry), data.getPointer());
            if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                return false;
            }
            try {
                data.read();
                return (data.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0
                        && (data.dwReserved0 & NAME_SURROGATE_BIT) != 0;
            } finally {
                Kernel32.INSTANCE.FindClose(handle);
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Win32 accepts paths longer than MAX_PATH only with the {@code \\?\} prefix. */
    private static String win32Path(Path entry) {
        String path = entry.toAbsolutePath().toString();
        if (path.length() < 248 || path.startsWith("\\\\?\\")) {
            return path;
        }
        return path.startsWith("\\\\") ? "\\\\?\\UNC\\" + path.substring(2) : "\\\\?\\" + path;
    }
}
