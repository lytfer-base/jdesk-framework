package com.jdesk.frame.ui;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Native file and folder pickers. On Windows they call the Common Item Dialog (IFileOpenDialog, IFileSaveDialog),
 * the modern Explorer-style dialog; {@link JFileChooser} and {@link FileDialog} look dated there and serve only as
 * the fallback elsewhere. Each call runs on a temporary STA thread and returns a {@link CompletableFuture}; a shown
 * {@code owner} becomes the modal parent, and null means no parent.
 * Don't join or get the result on the EDT or a WebView render thread: the dialog and its callbacks can deadlock.
 */
public final class NativeFileDialogs {

    /** File type filter: {@code label} is shown in the type list; {@code pattern} looks like {@code *.jpg;*.png}. */
    public record FileTypeFilter(String label, String pattern) {
        public FileTypeFilter {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(NativeFileDialogs.class);

    // COM identities and vtable slots of the Common Item Dialog; open and save share the slots below 27.
    private static final Guid.CLSID CLSID_FILE_OPEN_DIALOG = new Guid.CLSID("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}");
    private static final Guid.IID IID_IFILE_OPEN_DIALOG = new Guid.IID("{D57C7288-D4AD-4768-BE02-9D969532D960}");
    private static final Guid.CLSID CLSID_FILE_SAVE_DIALOG = new Guid.CLSID("{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}");
    private static final Guid.IID IID_IFILE_SAVE_DIALOG = new Guid.IID("{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}");
    private static final int VT_RELEASE = 2;
    private static final int VT_SHOW = 3;
    private static final int VT_SET_FILE_TYPES = 4;
    private static final int VT_GET_OPTIONS = 10;
    private static final int VT_SET_OPTIONS = 9;
    private static final int VT_SET_FILE_NAME = 15;
    private static final int VT_SET_TITLE = 17;
    private static final int VT_GET_RESULT = 20;
    private static final int VT_SET_DEFAULT_EXTENSION = 22;
    private static final int VT_GET_RESULTS = 27; // IFileOpenDialog only
    private static final int VT_SHELL_ITEM_GET_DISPLAY_NAME = 5;
    private static final int VT_ITEM_ARRAY_GET_COUNT = 7;
    private static final int VT_ITEM_ARRAY_GET_ITEM_AT = 8;
    private static final int FOS_PICKFOLDERS = 0x20;
    private static final int FOS_FORCEFILESYSTEM = 0x40;
    private static final int FOS_ALLOWMULTISELECT = 0x200;
    private static final int SIGDN_FILESYSPATH = 0x80058000;
    /** HRESULT from Show when the user cancels: HRESULT_FROM_WIN32(ERROR_CANCELLED). */
    private static final int HR_CANCELLED = 0x800704C7;

    private NativeFileDialogs() {
    }

    /** Picks one file; empty if cancelled. */
    public static CompletableFuture<Optional<Path>> pickFile(Window owner, String title, List<FileTypeFilter> filters) {
        if (!isWindows()) {
            return fallbackAwtFiles(owner, title, filters, false).thenApply(NativeFileDialogs::firstOf);
        }
        return onStaThread(() -> {
            List<Path> picked = showCommonDialog(new DialogSpec(
                    false, owner, title, filters, 0, false, null));
            return firstOf(picked);
        });
    }

    /** Picks several files; an empty list if cancelled. */
    public static CompletableFuture<List<Path>> pickFiles(Window owner, String title, List<FileTypeFilter> filters) {
        if (!isWindows()) {
            return fallbackAwtFiles(owner, title, filters, true);
        }
        return onStaThread(() -> showCommonDialog(new DialogSpec(
                false, owner, title, filters, FOS_ALLOWMULTISELECT, true, null)));
    }

    /** Picks a folder; empty if cancelled. */
    public static CompletableFuture<Optional<Path>> pickFolder(Window owner, String title) {
        if (!isWindows()) {
            return fallbackSwingFolder(owner, title);
        }
        return onStaThread(() -> firstOf(showCommonDialog(new DialogSpec(
                false, owner, title, List.of(), FOS_PICKFOLDERS, false, null))));
    }

    /** Picks a save target prefilled with {@code defaultFileName}, defaulting to its extension; empty if cancelled. */
    public static CompletableFuture<Optional<Path>> pickSaveFile(
            Window owner, String title, String defaultFileName, List<FileTypeFilter> filters) {
        if (!isWindows()) {
            return fallbackAwtSave(owner, title, defaultFileName);
        }
        return onStaThread(() -> firstOf(showCommonDialog(new DialogSpec(
                true, owner, title, filters, 0, false, defaultFileName))));
    }

    // ---- Windows: Common Item Dialog ----

    private record DialogSpec(boolean save, Window owner, String title, List<FileTypeFilter> filters,
                              int extraOptions, boolean multi, String defaultFileName) {
    }

    /** Shows the dialog on the current STA thread and returns the picked paths; an empty list if cancelled. */
    private static List<Path> showCommonDialog(DialogSpec spec) {
        WinNT.HRESULT init = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED);
        try {
            PointerByReference ppv = new PointerByReference();
            WinNT.HRESULT created = Ole32.INSTANCE.CoCreateInstance(
                    spec.save() ? CLSID_FILE_SAVE_DIALOG : CLSID_FILE_OPEN_DIALOG,
                    null,
                    WTypes.CLSCTX_INPROC_SERVER,
                    spec.save() ? IID_IFILE_SAVE_DIALOG : IID_IFILE_OPEN_DIALOG,
                    ppv);
            if (created.intValue() < 0) {
                throw new IllegalStateException("CoCreateInstance(FileDialog) failed: hr=0x"
                        + Integer.toHexString(created.intValue()));
            }
            Pointer dialog = ppv.getValue();
            try {
                if (spec.title() != null) {
                    comCall(dialog, VT_SET_TITLE, new WString(spec.title()));
                }
                // The dialog keeps only pointers, so the filter memory must stay referenced until Show returns.
                List<Memory> filterKeepAlive = new ArrayList<>();
                if (!spec.filters().isEmpty()) {
                    Memory specs = buildFilterSpecs(spec.filters(), filterKeepAlive);
                    check(comCall(dialog, VT_SET_FILE_TYPES, spec.filters().size(), specs), "SetFileTypes");
                    filterKeepAlive.add(specs);
                }
                // Add to the default options, keeping system defaults such as FOS_PATHMUSTEXIST.
                IntByReference options = new IntByReference();
                check(comCall(dialog, VT_GET_OPTIONS, options), "GetOptions");
                check(comCall(dialog, VT_SET_OPTIONS, options.getValue() | FOS_FORCEFILESYSTEM | spec.extraOptions()),
                        "SetOptions");
                if (spec.defaultFileName() != null && !spec.defaultFileName().isBlank()) {
                    comCall(dialog, VT_SET_FILE_NAME, new WString(spec.defaultFileName()));
                    String ext = extensionOf(spec.defaultFileName());
                    if (!ext.isEmpty()) {
                        comCall(dialog, VT_SET_DEFAULT_EXTENSION, new WString(ext));
                    }
                }
                int shown = comCall(dialog, VT_SHOW, ownerHwnd(spec.owner()));
                if (shown == HR_CANCELLED) {
                    return List.of();
                }
                check(shown, "Show");
                return spec.multi() ? readResults(dialog) : readSingleResult(dialog);
            } finally {
                comCall(dialog, VT_RELEASE);
            }
        } finally {
            // Both S_OK and S_FALSE need CoUninitialize; RPC_E_CHANGED_MODE (thread already MTA) must not.
            if (init.intValue() >= 0) {
                Ole32.INSTANCE.CoUninitialize();
            }
        }
    }

    private static List<Path> readSingleResult(Pointer dialog) {
        PointerByReference item = new PointerByReference();
        check(comCall(dialog, VT_GET_RESULT, item), "GetResult");
        return List.of(shellItemPath(item.getValue()));
    }

    private static List<Path> readResults(Pointer dialog) {
        PointerByReference array = new PointerByReference();
        check(comCall(dialog, VT_GET_RESULTS, array), "GetResults");
        Pointer itemArray = array.getValue();
        try {
            IntByReference count = new IntByReference();
            check(comCall(itemArray, VT_ITEM_ARRAY_GET_COUNT, count), "GetCount");
            List<Path> paths = new ArrayList<>(count.getValue());
            for (int i = 0; i < count.getValue(); i++) {
                PointerByReference item = new PointerByReference();
                check(comCall(itemArray, VT_ITEM_ARRAY_GET_ITEM_AT, i, item), "GetItemAt");
                paths.add(shellItemPath(item.getValue()));
            }
            return paths;
        } finally {
            comCall(itemArray, VT_RELEASE);
        }
    }

    /** Returns the file system path of an IShellItem (SIGDN_FILESYSPATH) and releases the item and the COM string. */
    private static Path shellItemPath(Pointer shellItem) {
        try {
            PointerByReference name = new PointerByReference();
            check(comCall(shellItem, VT_SHELL_ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, name), "GetDisplayName");
            try {
                return Path.of(name.getValue().getWideString(0));
            } finally {
                Ole32.INSTANCE.CoTaskMemFree(name.getValue());
            }
        } finally {
            comCall(shellItem, VT_RELEASE);
        }
    }

    /** Builds COMDLG_FILTERSPEC[n], 2n consecutive wide-string pointers; each string's Memory goes into keepAlive. */
    static Memory buildFilterSpecs(List<FileTypeFilter> filters, List<Memory> keepAlive) {
        Memory specs = new Memory(2L * filters.size() * Native.POINTER_SIZE);
        for (int i = 0; i < filters.size(); i++) {
            Memory label = wideString(filters.get(i).label());
            Memory pattern = wideString(filters.get(i).pattern());
            keepAlive.add(label);
            keepAlive.add(pattern);
            specs.setPointer((2L * i) * Native.POINTER_SIZE, label);
            specs.setPointer((2L * i + 1) * Native.POINTER_SIZE, pattern);
        }
        return specs;
    }

    /** Returns the lowercase extension without the dot, as SetDefaultExtension expects, or an empty string. */
    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Memory wideString(String s) {
        Memory m = new Memory((s.length() + 1L) * 2);
        m.setWideString(0, s);
        return m;
    }

    /** Calls a COM vtable slot directly (the standard calling convention on x64), passing the object as this. */
    private static int comCall(Pointer comObject, int slot, Object... args) {
        Pointer vtable = comObject.getPointer(0);
        Function fn = Function.getFunction(vtable.getPointer((long) slot * Native.POINTER_SIZE));
        Object[] full = new Object[args.length + 1];
        full[0] = comObject;
        System.arraycopy(args, 0, full, 1, args.length);
        return fn.invokeInt(full);
    }

    private static void check(int hr, String what) {
        if (hr < 0) {
            throw new IllegalStateException("IFileDialog." + what + " failed: hr=0x" + Integer.toHexString(hr));
        }
    }

    /** Returns the owner's HWND, safe on any thread; a null or undisplayable owner yields no parent. */
    private static Pointer ownerHwnd(Window owner) {
        if (owner == null || !owner.isDisplayable()) {
            return Pointer.NULL;
        }
        try {
            return Native.getWindowPointer(owner);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            LOG.warn("nativeFileDialogs.ownerHwndUnavailable", e);
            return Pointer.NULL;
        }
    }

    private static <T> CompletableFuture<T> onStaThread(DialogTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                future.complete(task.run());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "jdf-native-file-dialog");
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    private interface DialogTask<T> {
        T run();
    }

    private static <T> Optional<T> firstOf(List<T> list) {
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ---- Fallback outside Windows: plain but working ----

    private static CompletableFuture<List<Path>> fallbackAwtFiles(
            Window owner, String title, List<FileTypeFilter> filters, boolean multi) {
        CompletableFuture<List<Path>> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                FileDialog dialog = owned(owner, title, FileDialog.LOAD);
                if (!filters.isEmpty()) {
                    dialog.setFile(filters.get(0).pattern());
                }
                dialog.setMultipleMode(multi);
                dialog.setVisible(true);
                List<Path> picked = new ArrayList<>();
                for (File file : dialog.getFiles()) {
                    picked.add(file.toPath());
                }
                future.complete(picked);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static CompletableFuture<Optional<Path>> fallbackAwtSave(Window owner, String title, String defaultFileName) {
        CompletableFuture<Optional<Path>> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                FileDialog dialog = owned(owner, title, FileDialog.SAVE);
                if (defaultFileName != null) {
                    dialog.setFile(defaultFileName);
                }
                dialog.setVisible(true);
                future.complete(dialog.getFile() == null
                        ? Optional.empty()
                        : Optional.of(Path.of(dialog.getDirectory(), dialog.getFile())));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static CompletableFuture<Optional<Path>> fallbackSwingFolder(Window owner, String title) {
        // AWT FileDialog can't pick folders, so use JFileChooser.
        CompletableFuture<Optional<Path>> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(title);
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int result = chooser.showOpenDialog(owner);
                future.complete(result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null
                        ? Optional.of(chooser.getSelectedFile().toPath())
                        : Optional.empty());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static FileDialog owned(Window owner, String title, int mode) {
        if (owner instanceof Frame frame) {
            return new FileDialog(frame, title, mode);
        }
        if (owner instanceof java.awt.Dialog dialog) {
            return new FileDialog(dialog, title, mode);
        }
        FileDialog dialog = new FileDialog((Frame) null, title, mode);
        return dialog;
    }
}
