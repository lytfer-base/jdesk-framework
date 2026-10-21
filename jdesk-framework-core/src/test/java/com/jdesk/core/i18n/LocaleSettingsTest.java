package com.jdesk.core.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocaleSettingsTest {

    @Test
    void missingFileMeansSystem(@TempDir Path dir) {
        assertEquals("system", LocaleSettings.read(dir));
    }

    @Test
    void writeThenRead(@TempDir Path dir) throws Exception {
        LocaleSettings.write(dir, "ja");
        assertEquals("ja", LocaleSettings.read(dir));
        String text = Files.readString(dir.resolve("locale.properties"), StandardCharsets.UTF_8);
        assertEquals(true, text.contains("language=ja"));
    }

    @Test
    void blankValueMeansSystem(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("locale.properties"), "# comment\nlanguage=  \n", StandardCharsets.UTF_8);
        assertEquals("system", LocaleSettings.read(dir));
    }

    @Test
    void malformedEscapeMeansSystem(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("locale.properties"), "language=\\uZZZZ\n", StandardCharsets.UTF_8);
        assertEquals("system", LocaleSettings.read(dir));
    }
}
