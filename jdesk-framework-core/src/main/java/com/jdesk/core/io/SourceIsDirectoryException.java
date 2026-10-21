package com.jdesk.core.io;

import java.nio.file.FileSystemException;

/**
 * The source of a file copy is a directory: a logic error that no amount of waiting will fix.
 * Don't change it back to a plain {@link FileSystemException}; callers retry those as "file in use by another program".
 */
public final class SourceIsDirectoryException extends FileSystemException {

    public SourceIsDirectoryException(String file) {
        super(file, null, "Is a directory");
    }
}
