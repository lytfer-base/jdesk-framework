package com.jdesk.util.xml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * XML serialization / deserialization convenience methods.
 * Wraps Jackson XmlMapper for common use cases.
 */
public final class XmlHelper {

    private static final ObjectMapper DEFAULT_MAPPER = new XmlMapper();

    private XmlHelper() {}

    /**
     * Serialize an object to XML string.
     */
    public static String toXml(Object obj) {
        try {
            return DEFAULT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize to XML: " + obj.getClass().getName(), e);
        }
    }

    /**
     * Deserialize XML string to object.
     */
    public static <T> T fromXml(String xml, Class<T> clazz) {
        try {
            return DEFAULT_MAPPER.readValue(xml, clazz);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize from XML: " + clazz.getName(), e);
        }
    }

    /**
     * Write object to XML file.
     */
    public static void writeToFile(Path path, Object obj) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            DEFAULT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), obj);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write XML to: " + path, e);
        }
    }

    /**
     * Read object from XML file.
     */
    public static <T> T readFromFile(Path path, Class<T> clazz) {
        try {
            return DEFAULT_MAPPER.readValue(path.toFile(), clazz);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read XML from: " + path, e);
        }
    }
}
