package com.jdesk.frame.vfs;

import com.jdesk.core.vfs.NativeFileIdReader;

import java.util.Locale;

/**
 * Picks the {@link NativeFileIdReader} for the current platform, falling back to
 * {@link NativeFileIdReader#UNSUPPORTED}, which disables the second matching clue but not path matching.
 * Keep platform specifics here, out of the public API.
 */
public final class NativeFileIdReaders {

    private NativeFileIdReaders() {
    }

    /** Returns the Win32 reader on Windows and {@link NativeFileIdReader#UNSUPPORTED} elsewhere. */
    public static NativeFileIdReader forCurrentPlatform() {
        return isWindows() ? new WindowsNativeFileIdReader() : NativeFileIdReader.UNSUPPORTED;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
