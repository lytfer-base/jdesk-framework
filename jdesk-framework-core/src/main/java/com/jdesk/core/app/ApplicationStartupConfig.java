package com.jdesk.core.app;

import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.runtime.RuntimeParamSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads startup application properties into the runtime parameter registry.
 */
public final class ApplicationStartupConfig {

    public static final String APPLICATION_PROPERTIES = "application.properties";

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationStartupConfig.class);

    private ApplicationStartupConfig() {
    }

    public static void load() {
        RuntimeParamRegistry.reset();
        Properties properties = readProperties();
        for (String key : properties.stringPropertyNames()) {
            RuntimeParamRegistry.setStartupValue(key, properties.getProperty(key), RuntimeParamSource.STARTUP_CONFIG);
        }
    }

    /** Reads the startup configuration as UTF-8, so non-ASCII keys and values need no Unicode escapes. */
    public static Properties readProperties() {
        Path propertiesFile = getApplicationPropertiesPath();
        Properties properties = new Properties();
        if (!Files.isRegularFile(propertiesFile)) {
            return properties;
        }

        try (Reader reader = new InputStreamReader(Files.newInputStream(propertiesFile), lenientUtf8())) {
            properties.load(reader);
        } catch (IOException e) {
            LOG.warn("Failed to read application properties from {}", propertiesFile, e);
        }
        return properties;
    }

    /** Replaces bad bytes with U+FFFD; don't make it strict, or one GBK-encoded comment would lose every parameter. */
    private static CharsetDecoder lenientUtf8() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    public static Path getApplicationPropertiesPath() {
        return PathManager.getSystemDir().resolve(APPLICATION_PROPERTIES);
    }
}
