package com.jdesk.core.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageNegotiatorTest {

    @Test
    void sharedCasesMatch() throws Exception {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/i18n/negotiation-cases.json")) {
            cases = new ObjectMapper().readTree(in);
        }
        assertTrue(cases.size() > 10, "the case file was not read");
        for (JsonNode c : cases) {
            List<String> os = new ArrayList<>();
            c.get("os").forEach(tag -> os.add(tag.asText()));
            UiLanguage actual = LanguageNegotiator.negotiate(c.get("setting").asText(), os);
            assertEquals(c.get("expected").asText(), actual.tag(), c.toString());
        }
    }

    @Test
    void fromTagAcceptsUnderscoreAndCase() {
        assertEquals(UiLanguage.ZH_CN, UiLanguage.fromTag("zh_cn").orElseThrow());
        assertEquals(UiLanguage.JA, UiLanguage.fromTag("JA").orElseThrow());
        assertTrue(UiLanguage.fromTag("fr").isEmpty());
    }

    @Test
    void localesAreRegionalized() {
        assertEquals(new Locale("zh", "CN"), UiLanguage.ZH_CN.locale());
        assertEquals(new Locale("ja", "JP"), UiLanguage.JA.locale());
        assertEquals(new Locale("en", "US"), UiLanguage.EN.locale());
        assertEquals(new Locale("en", "XA"), UiLanguage.PSEUDO.locale());
        assertEquals("zh_CN", UiLanguage.ZH_CN.fileSuffix());
        assertEquals("en", UiLanguage.EN.fileSuffix());
        assertEquals("ja", UiLanguage.JA.fileSuffix());
        assertEquals("en", UiLanguage.PSEUDO.fileSuffix());
    }

    @Test
    void onlyPseudoIsPseudo() {
        assertTrue(UiLanguage.PSEUDO.isPseudo());
        assertFalse(UiLanguage.EN.isPseudo());
    }

    @Test
    void cjkFallbackFollowsLanguageThenOs() {
        assertEquals("ja", LanguageNegotiator.cjkFallback(UiLanguage.JA, List.of("zh-CN")));
        assertEquals("zh", LanguageNegotiator.cjkFallback(UiLanguage.ZH_CN, List.of("ja-JP")));
        assertEquals("ja", LanguageNegotiator.cjkFallback(UiLanguage.EN, List.of("en-US", "ja-JP")));
        assertEquals("zh", LanguageNegotiator.cjkFallback(UiLanguage.EN, List.of("en-US")));
        assertEquals("zh", LanguageNegotiator.cjkFallback(UiLanguage.EN, List.of("zh-CN", "ja-JP")));
        assertEquals("ja", LanguageNegotiator.cjkFallback(UiLanguage.PSEUDO, List.of("ja-JP")));
    }
}
