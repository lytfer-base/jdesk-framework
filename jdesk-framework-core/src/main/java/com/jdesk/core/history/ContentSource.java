package com.jdesk.core.history;

import java.nio.file.Path;
import java.util.Objects;

/** Where captured content comes from: bytes in memory, or a file on disk that the store reads itself. */
public sealed interface ContentSource {

    record Bytes(byte[] data) implements ContentSource {
        public Bytes {
            Objects.requireNonNull(data, "data");
        }
    }

    record Disk(Path path) implements ContentSource {
        public Disk {
            Objects.requireNonNull(path, "path");
        }
    }

    static ContentSource bytes(byte[] data) {
        return new Bytes(data);
    }

    static ContentSource disk(Path path) {
        return new Disk(path);
    }
}
