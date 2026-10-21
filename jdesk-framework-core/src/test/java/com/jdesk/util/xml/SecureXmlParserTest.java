package com.jdesk.util.xml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureXmlParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesSimpleXml() {
        Document document = SecureXmlParser.parse(xml("""
                <?xml version="1.0"?>
                <root>
                  <child>value</child>
                </root>
                """), "simple");

        assertEquals("root", document.getDocumentElement().getNodeName());
        assertEquals("value", document.getDocumentElement()
                .getElementsByTagName("child")
                .item(0)
                .getTextContent());
    }

    @Test
    void rejectsDoctypeAndExternalEntityDeclarations() throws Exception {
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "should-not-be-read");

        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE root [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <root>&xxe;</root>
                """.formatted(secret.toUri());

        assertThrows(IllegalArgumentException.class,
                () -> SecureXmlParser.parse(xml(xml), "xxe"));
    }

    private static ByteArrayInputStream xml(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
