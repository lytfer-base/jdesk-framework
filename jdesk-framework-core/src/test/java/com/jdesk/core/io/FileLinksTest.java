package com.jdesk.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class FileLinksTest {

    @TempDir
    Path dir;

    /**
     * A junction is a link and paths below it are reached through a link; ordinary folders and files are not, and
     * neither is the starting path itself.
     */
    @Test
    void aJunctionIsALinkWhileOrdinaryFoldersAndFilesAreNot() throws Exception {
        Path target = Files.createDirectories(dir.resolve("target"));
        Path folder = Files.createDirectories(dir.resolve("folder"));
        Path file = Files.writeString(folder.resolve("a.txt"), "a");
        Path junction = dir.resolve("junction");
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString())
                .redirectErrorStream(true).start();
        String output = new String(mklink.getInputStream().readAllBytes());
        assertEquals(0, mklink.waitFor(), output);
        try {
            assertTrue(FileLinks.isLink(junction));
            assertTrue(FileLinks.isLink(junction,
                    Files.readAttributes(junction, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)));
            assertFalse(FileLinks.isLink(folder));
            assertFalse(FileLinks.isLink(file));
            assertFalse(FileLinks.isLink(dir.resolve("missing")));

            assertTrue(FileLinks.reachedThroughLink(dir, junction.resolve("inner")));
            assertFalse(FileLinks.reachedThroughLink(dir, file));
            assertFalse(FileLinks.reachedThroughLink(junction, junction));
        } finally {
            // Delete the junction right here; don't leave it to the @TempDir cleanup.
            Files.deleteIfExists(junction);
        }
    }
}
