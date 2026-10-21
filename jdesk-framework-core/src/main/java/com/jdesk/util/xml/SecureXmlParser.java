package com.jdesk.util.xml;

import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hardened DOM XML parser for framework and plugin descriptors.
 */
@SuppressWarnings("HttpUrlsUsage")
public final class SecureXmlParser {

    private SecureXmlParser() {
    }

    public static Document parse(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return parse(input, path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read XML: " + path, e);
        }
    }

    public static Document parse(InputStream input, String sourceDescription) {
        try {
            DocumentBuilder builder = newDocumentBuilder();
            return builder.parse(input);
        } catch (SAXException e) {
            throw new IllegalArgumentException("Failed to parse XML: " + sourceDescription, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read XML: " + sourceDescription, e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Failed to create secure XML parser", e);
        }
    }

    private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.setErrorHandler(new ThrowingErrorHandler());
        return builder;
    }

    private static final class ThrowingErrorHandler implements ErrorHandler {
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
