package com.jdesk.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class PathAccessPolicyTest {
    @TempDir Path root;

    @Test
    void missingAndOutsideRootsAreRejectedWithoutCreatingThem() throws Exception {
        Path absent = root.resolve("absent");
        var policy = PathAccessPolicy.existingRoot(absent);
        assertThrows(IOException.class, () -> policy.checkAccess(absent.resolve("file")));
        assertFalse(Files.exists(absent));
        Files.createDirectory(absent);
        policy.checkAccess(absent.resolve("file"));
        assertThrows(IOException.class, () -> policy.checkAccess(root.resolve("sibling")));
        Files.move(absent, root.resolve("moved"));
        assertThrows(IOException.class, () -> policy.checkAccess(absent.resolve("file")));
        assertFalse(Files.exists(absent));
    }

    @Test
    void nestedScopesCannotRelaxABoundaryAndRestoreAfterFailure() {
        var policy = PathAccessPolicy.existingRoot(root);
        assertThrows(IllegalArgumentException.class, () -> PathAccessPolicy.existingRoot(Path.of("relative")));
        assertThrows(IllegalStateException.class, () -> {
            try (var outer = policy.enter(); var defaultScope = PathAccessPolicy.createMissing().enter()) {
                assertEquals(policy, PathAccessPolicy.current());
                try (var narrow = PathAccessPolicy.existingRoot(root.resolve("child")).enter()) {
                    assertEquals(root.resolve("child"), PathAccessPolicy.current().root());
                }
                assertEquals(policy, PathAccessPolicy.current());
                throw new IllegalStateException("application failure");
            }
        });
        assertEquals(PathAccessPolicy.createMissing(), PathAccessPolicy.current());
    }

    @Test
    void scopesRejectCrossThreadAndOutOfOrderClose() {
        var outer = PathAccessPolicy.existingRoot(root).enter();
        try {
            var inner = PathAccessPolicy.createMissing().enter();
            try {
                assertThrows(IllegalStateException.class, outer::close);
                CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, inner::close)).join();
            } finally {
                inner.close();
            }
        } finally {
            outer.close();
        }
        assertFalse(PathAccessPolicy.current().requiresExistingRoot());
    }

    @Test
    void unrelatedNestedRootsAreRejectedWithoutChangingTheCurrentScope() {
        var policy = PathAccessPolicy.existingRoot(root.resolve("one"));
        try (var scope = policy.enter()) {
            assertThrows(IllegalStateException.class,
                    () -> PathAccessPolicy.existingRoot(root.resolve("two")).enter());
            assertEquals(policy, PathAccessPolicy.current());
        }
        assertFalse(PathAccessPolicy.current().requiresExistingRoot());
    }
}
