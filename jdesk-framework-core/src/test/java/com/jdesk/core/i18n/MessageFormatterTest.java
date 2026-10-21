package com.jdesk.core.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageFormatterTest {

    @Test
    void sharedCasesMatch() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/i18n/format-cases.json")) {
            cases = mapper.readTree(in);
        }
        assertTrue(cases.size() > 10, "the case file was not read");
        for (JsonNode c : cases) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = mapper.convertValue(c.get("params"), Map.class);
            assertEquals(c.get("expected").asText(), MessageFormatter.format(c.get("pattern").asText(), params), c.toString());
        }
    }

    @Test
    void missingParameterStaysVisible() {
        assertEquals("Hello {name}", MessageFormatter.format("Hello {name}", Map.of()));
    }

    @Test
    void pluralWithoutCountUsesLastSegment() {
        assertEquals("{count} items", MessageFormatter.format("{count} item | {count} items", Map.of()));
    }
}
