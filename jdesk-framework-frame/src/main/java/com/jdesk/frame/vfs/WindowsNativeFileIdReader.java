package com.jdesk.frame.vfs;

import com.jdesk.core.vfs.NativeFileIdReader;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Native file identity on Windows: the volume serial number plus the 128-bit file id from {@code FILE_ID_INFO}, read
 * through Win32 because the JDK's {@code BasicFileAttributes.fileKey()} is always null there.
 * Don't compare MFT record numbers alone: a file that reuses a deleted one's record differs from it only in the
 * sequence number, which the id includes.
 */
final class WindowsNativeFileIdReader implements NativeFileIdReader {

    private static final Logger LOG = LoggerFactory.getLogger(WindowsNativeFileIdReader.class);

    /** Shares read, write and delete, so asking for an identity never blocks other programs. */
    private static final int FILE_SHARE_ALL = 0x1 | 0x2 | 0x4;
    private static final int OPEN_EXISTING = 3;
    /** Required to open directory handles; directories need identities too, so renamed folders are recognized. */
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    /** FILE_INFO_BY_HANDLE_CLASS.FileIdInfo; it is 18, don't confuse it with the directory classes 10 and 11. */
    private static final int FILE_ID_INFO = 18;
    /** sizeof(FILE_ID_INFO): a ULONGLONG volume serial number plus a BYTE[16] file id. */
    private static final int FILE_ID_INFO_SIZE = 24;
    /** FILE_INFO_BY_HANDLE_CLASS.FileIdBothDirectoryInfo and its RestartInfo: 10 and 11, not 7 and 8. */
    private static final int FILE_ID_BOTH_DIRECTORY_INFO = 10;
    private static final int FILE_ID_BOTH_DIRECTORY_RESTART_INFO = 11;
    /** Access to list a directory; zero access works per file but enumeration then fails with ERROR_HANDLE_EOF. */
    private static final int FILE_LIST_DIRECTORY = 0x0001;
    /** Field offsets in FILE_ID_BOTH_DIR_INFO. */
    private static final int DIR_NEXT_ENTRY_OFFSET = 0;
    private static final int DIR_FILE_NAME_LENGTH = 60;
    private static final int DIR_FILE_ID = 96;
    private static final int DIR_FILE_NAME = 104;
    /** Buffer for one batch of directory entries; a smaller one only means more calls. */
    private static final int DIR_BUFFER_BYTES = 64 * 1024;
    /** Longest path that opens without the long-path prefix; the same limit the JDK uses for its own Win32 calls. */
    private static final int MAX_PLAIN_PATH = 247;

    @Override
    public Optional<String> identityOf(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        String path = win32Path(file);
        // Zero access asks for metadata only, so locked and read-only files still yield an identity.
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(path, 0, FILE_SHARE_ALL, null,
                OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            // Missing, denied or unsupported: no clue, never an exception, since files may vanish mid-scan.
            return Optional.empty();
        }
        try {
            Memory buffer = new Memory(FILE_ID_INFO_SIZE);
            if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(
                    handle, FILE_ID_INFO, buffer, new WinDef.DWORD(FILE_ID_INFO_SIZE))) {
                LOG.debug("vfs.nativeId.unavailable path={} err={}", path, Kernel32.INSTANCE.GetLastError());
                return Optional.empty();
            }
            long volumeSerial = buffer.getLong(0);
            byte[] fileId = buffer.getByteArray(8, 16);
            // Must include the volume serial: file ids repeat across volumes, and a junction can span volumes.
            return Optional.of(format(volumeSerial, fileId));
        } catch (RuntimeException e) {
            LOG.debug("vfs.nativeId.failed path={}", path, e);
            return Optional.empty();
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    /**
     * Reads the identities of a directory's children through one directory handle, a call or two per directory
     * instead of one per file. The volume serial is read once, since the children share the directory's volume.
     */
    @Override
    public Map<String, String> identitiesIn(Path directory) {
        if (directory == null) {
            return Map.of();
        }
        String path = win32Path(directory);
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(path, FILE_LIST_DIRECTORY, FILE_SHARE_ALL,
                null, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            return Map.of(); // Missing or denied: no bulk result, callers ask per file.
        }
        try {
            Memory idInfo = new Memory(FILE_ID_INFO_SIZE);
            if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(
                    handle, FILE_ID_INFO, idInfo, new WinDef.DWORD(FILE_ID_INFO_SIZE))) {
                return Map.of();
            }
            long volumeSerial = idInfo.getLong(0);

            Map<String, String> identities = new HashMap<>();
            Memory buffer = new Memory(DIR_BUFFER_BYTES);
            // The restart class rewinds the cursor first; the plain class continues until ERROR_NO_MORE_FILES.
            int infoClass = FILE_ID_BOTH_DIRECTORY_RESTART_INFO;
            while (Kernel32.INSTANCE.GetFileInformationByHandleEx(
                    handle, infoClass, buffer, new WinDef.DWORD(DIR_BUFFER_BYTES))) {
                infoClass = FILE_ID_BOTH_DIRECTORY_INFO;
                // Variable-length FILE_ID_BOTH_DIR_INFO entries: NextEntryOffset leads on, 0 ends the batch.
                long offset = 0;
                while (true) {
                    int next = buffer.getInt(offset + DIR_NEXT_ENTRY_OFFSET);
                    int nameBytes = buffer.getInt(offset + DIR_FILE_NAME_LENGTH);
                    long fileId = buffer.getLong(offset + DIR_FILE_ID);
                    String name = new String(buffer.getCharArray(offset + DIR_FILE_NAME, nameBytes / 2));
                    if (!".".equals(name) && !"..".equals(name)) {
                        identities.put(name, format(volumeSerial, expandToFileId128(fileId)));
                    }
                    if (next == 0) {
                        break;
                    }
                    offset += next;
                }
            }
            return Map.copyOf(identities);
        } catch (RuntimeException e) {
            LOG.debug("vfs.nativeId.bulkFailed path={}", path, e);
            return Map.of();
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    /**
     * Zero-extends a 64-bit id from directory enumeration to the little-endian 128-bit form, matching NTFS.
     * Must match {@link #identityOf}, or one file gets two identities; see bulkIdentitiesAgreeWithPerFileLookup.
     */
    private static byte[] expandToFileId128(long fileId) {
        byte[] bytes = new byte[16];
        for (int i = 0; i < Long.BYTES; i++) {
            bytes[i] = (byte) (fileId >>> (Byte.SIZE * i));
        }
        return bytes;
    }

    private static String format(long volumeSerial, byte[] fileId128) {
        return Long.toHexString(volumeSerial) + ':' + HexFormat.of().formatHex(fileId128);
    }

    /** FILE_ID_DESCRIPTOR: DWORD dwSize, FILE_ID_TYPE Type, then a union at offset 8 holding the FILE_ID_128. */
    private static final int FILE_ID_DESCRIPTOR_SIZE = 24;
    private static final int FILE_ID_DESCRIPTOR_ID_OFFSET = 8;
    private static final int EXTENDED_FILE_ID_TYPE = 2;
    private static final int ERROR_FILE_NOT_FOUND = 2;
    private static final int ERROR_PATH_NOT_FOUND = 3;
    private static final int ERROR_INVALID_PARAMETER = 87;
    private static final int FINAL_PATH_BUFFER_CHARS = 32 * 1024;

    /**
     * Finds an identity's current path with {@code OpenFileById} and {@code GetFinalPathNameByHandle}; the hint must
     * be on the identity's volume, otherwise returns {@link Location#unknown()}.
     */
    @Override
    public Location locate(String identity, Path volumeHint) {
        if (identity == null || volumeHint == null) {
            return Location.unknown();
        }
        int colon = identity.indexOf(':');
        if (colon <= 0 || identity.length() - colon - 1 != 32) {
            return Location.unknown();
        }
        long volumeSerial;
        byte[] fileId;
        try {
            volumeSerial = Long.parseUnsignedLong(identity.substring(0, colon), 16);
            fileId = HexFormat.of().parseHex(identity.substring(colon + 1));
        } catch (IllegalArgumentException malformed) {
            return Location.unknown();
        }
        String hintPath = win32Path(volumeHint);
        WinNT.HANDLE volume = Kernel32.INSTANCE.CreateFile(hintPath, 0, FILE_SHARE_ALL, null,
                OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, null);
        if (volume == null || WinBase.INVALID_HANDLE_VALUE.equals(volume)) {
            return Location.unknown();
        }
        try {
            Memory idInfo = new Memory(FILE_ID_INFO_SIZE);
            if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(
                    volume, FILE_ID_INFO, idInfo, new WinDef.DWORD(FILE_ID_INFO_SIZE))
                    || idInfo.getLong(0) != volumeSerial) {
                return Location.unknown();
            }
            Memory descriptor = new Memory(FILE_ID_DESCRIPTOR_SIZE);
            descriptor.clear();
            descriptor.setInt(0, FILE_ID_DESCRIPTOR_SIZE);
            descriptor.setInt(4, EXTENDED_FILE_ID_TYPE);
            descriptor.write(FILE_ID_DESCRIPTOR_ID_OFFSET, fileId, 0, fileId.length);
            WinNT.HANDLE file = Kernel32Ex.INSTANCE.OpenFileById(volume, descriptor, 0, FILE_SHARE_ALL,
                    null, FILE_FLAG_BACKUP_SEMANTICS);
            if (file == null || WinBase.INVALID_HANDLE_VALUE.equals(file)) {
                int error = Native.getLastError();
                boolean gone = error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND
                        || error == ERROR_INVALID_PARAMETER;
                if (!gone) {
                    LOG.debug("vfs.nativeId.locate.failed identity={} err={}", identity, error);
                }
                return gone ? Location.gone() : Location.unknown();
            }
            try {
                char[] buffer = new char[FINAL_PATH_BUFFER_CHARS];
                int length = Kernel32Ex.INSTANCE.GetFinalPathNameByHandleW(file, buffer, buffer.length, 0);
                if (length <= 0 || length >= buffer.length) {
                    return Location.unknown();
                }
                return Location.at(Paths.get(stripExtendedPrefix(new String(buffer, 0, length))));
            } finally {
                Kernel32.INSTANCE.CloseHandle(file);
            }
        } catch (RuntimeException e) {
            LOG.debug("vfs.nativeId.locate.failed identity={}", identity, e);
            return Location.unknown();
        } finally {
            Kernel32.INSTANCE.CloseHandle(volume);
        }
    }

    /**
     * Returns the path for CreateFile, adding the {@code \\?\} prefix ({@code \\?\UNC\} for UNC) beyond
     * {@value #MAX_PLAIN_PATH} characters, since the process isn't long-path aware.
     */
    static String win32Path(Path path) {
        String absolute = path.toAbsolutePath().toString();
        if (absolute.length() <= MAX_PLAIN_PATH || absolute.startsWith("\\\\?\\")) {
            return absolute;
        }
        String normalized = path.toAbsolutePath().normalize().toString();
        return normalized.startsWith("\\\\")
                ? "\\\\?\\UNC\\" + normalized.substring(2)
                : "\\\\?\\" + normalized;
    }

    /** Strips the extended-length prefix that GetFinalPathNameByHandle returns, for drive and UNC paths alike. */
    private static String stripExtendedPrefix(String path) {
        if (path.startsWith("\\\\?\\UNC\\")) {
            return "\\\\" + path.substring("\\\\?\\UNC\\".length());
        }
        if (path.startsWith("\\\\?\\")) {
            return path.substring("\\\\?\\".length());
        }
        return path;
    }

    /** Win32 functions that jna-platform's Kernel32 doesn't bind. */
    private interface Kernel32Ex extends StdCallLibrary {
        Kernel32Ex INSTANCE = Native.load("kernel32", Kernel32Ex.class);

        WinNT.HANDLE OpenFileById(WinNT.HANDLE volumeHint, Pointer fileIdDescriptor, int desiredAccess,
                                  int shareMode, Pointer securityAttributes, int flagsAndAttributes);

        int GetFinalPathNameByHandleW(WinNT.HANDLE file, char[] path, int pathChars, int flags);
    }
}
