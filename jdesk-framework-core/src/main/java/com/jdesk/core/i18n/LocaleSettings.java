package com.jdesk.core.i18n;

import com.jdesk.core.io.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads and writes {@code <config>/locale.properties}. Other runtimes may parse the file too, so it must hold only a
 * {@code language=<value>} line and {@code #} comment lines.
 */
public final class LocaleSettings {
    public static final String FILE_NAME = "locale.properties";
    public static final String KEY = "language";
    private static final Logger LOG = LoggerFactory.getLogger(LocaleSettings.class);

    private LocaleSettings() {
    }

    /** Returns the setting, or {@link LanguageNegotiator#SYSTEM} if the file is missing, empty or unreadable. */
    public static String read(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return LanguageNegotiator.SYSTEM;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            String value = properties.getProperty(KEY);
            return value == null || value.isBlank() ? LanguageNegotiator.SYSTEM : value.trim();
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("locale.settings.read.failed file={}", file, e);
            return LanguageNegotiator.SYSTEM;
        }
    }

    public static void write(Path configDir, String setting) throws IOException {
        Files.createDirectories(configDir);
        String text = "# Values: system, en, zh-CN, ja\n" + KEY + "=" + setting.trim() + "\n";
        AtomicFiles.write(configDir.resolve(FILE_NAME), text.getBytes(StandardCharsets.UTF_8));
    }
}
