package com.jdesk.core.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PseudoLocalizerTest {

    @Test
    void sharedCasesMatch() throws Exception {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/i18n/pseudo-cases.json")) {
            cases = new ObjectMapper().readTree(in);
        }
        assertTrue(cases.size() >= 5, "the case file was not read");
        for (JsonNode c : cases) {
            assertEquals(c.get("expected").asText(), PseudoLocalizer.pseudo(c.get("input").asText()), c.toString());
        }
    }
}
