package com.jdesk.util.xml;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XmlHelperTest {

    public static class Sample {
        public String name;
        public int count;
    }

    /** A single-segment relative path has no parent directory; writing it must succeed, not throw an NPE. */
    @Test
    void writesAndReadsBackSingleSegmentRelativePath() throws Exception {
        Path path = Path.of("xmlhelper-" + UUID.randomUUID() + ".xml");
        Sample sample = new Sample();
        sample.name = "demo";
        sample.count = 3;

        try {
            XmlHelper.writeToFile(path, sample);
            Sample back = XmlHelper.readFromFile(path, Sample.class);

            assertEquals("demo", back.name);
            assertEquals(3, back.count);
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
