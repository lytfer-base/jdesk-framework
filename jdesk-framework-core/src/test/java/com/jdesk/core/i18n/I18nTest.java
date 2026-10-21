package com.jdesk.core.i18n;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.app.TestApplication;
import com.jdesk.core.disposer.Disposer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nTest {
    @BeforeEach
    void setUp() {
        I18n.resetForTests();
        ClassLoader loader = getClass().getClassLoader();
        I18n.registerRoot("i18n-test/fw", loader, UiLanguage.EN);
        I18n.registerRoot("i18n-test/product", loader, UiLanguage.ZH_CN);
    }

    @AfterEach
    void tearDown() {
        I18n.resetForTests();
    }

    @Test
    void defaultsToSimplifiedChineseBeforeInitialization() {
        assertEquals(UiLanguage.ZH_CN, I18n.current());
        assertEquals("保存", I18n.message("demo.save"));
    }

    @Test
    void fallsBackToEnglishThenSource() {
        I18n.initialize(UiLanguage.JA);
        assertEquals("保存（ja）", I18n.message("demo.save"));
        assertEquals("3 items archived", I18n.message("demo.count", "count", 3));
        assertEquals("只有源语言", I18n.message("demo.onlySource"));
        assertEquals(new Locale("ja", "JP"), Locale.getDefault());
    }

    @Test
    void frameworkRootUsesEnglishAsSource() {
        I18n.initialize(UiLanguage.JA);
        assertEquals("OK", I18n.message("fw.ok"));
        I18n.initialize(UiLanguage.ZH_CN);
        assertEquals("确定", I18n.message("fw.ok"));
    }

    @Test
    void rootOwnsOnlyNamespacesWithItsSourceLanguageFile() {
        I18n.initialize(UiLanguage.ZH_CN);
        assertEquals("产品", I18n.message("shared.greeting"));
    }

    @Test
    void firstRegisteredOwningRootWins() {
        I18n.initialize(UiLanguage.ZH_CN);
        assertEquals("framework-en", I18n.message("both.value"));
    }

    @Test
    void unknownKeyReturnsKey() {
        assertEquals("nope.missing", I18n.message("nope.missing"));
        assertTrue(I18n.find("nope.missing").isEmpty());
        assertTrue(I18n.find("nope.missing", UiLanguage.JA).isEmpty());
    }

    @Test
    void findsAGivenLanguageWithoutSwitching() {
        I18n.initialize(UiLanguage.EN);
        assertEquals("保存（ja）", I18n.find("demo.save", UiLanguage.JA).orElseThrow());
        assertEquals("保存", I18n.find("demo.save", UiLanguage.ZH_CN).orElseThrow());
        assertEquals("只有源语言", I18n.find("demo.onlySource", UiLanguage.JA).orElseThrow(), "fallback as in message");
        assertEquals(UiLanguage.EN, I18n.current());
        assertEquals("Save", I18n.message("demo.save"));
    }

    /** The overload with parameters doesn't switch either and picks plurals by count in the target language. */
    @Test
    void findsAGivenLanguageWithParametersWithoutSwitching() {
        assertEquals("1 item archived", I18n.find("demo.count", UiLanguage.EN, Map.of("count", 1)).orElseThrow());
        assertEquals("3 items archived", I18n.find("demo.count", UiLanguage.EN, Map.of("count", 3)).orElseThrow());
        assertEquals("已归档 3 条", I18n.find("demo.count", UiLanguage.ZH_CN, Map.of("count", 3)).orElseThrow());
        assertEquals("3 items archived", I18n.find("demo.count", UiLanguage.JA, Map.of("count", 3)).orElseThrow(), "fallback as in message");
        assertTrue(I18n.find("nope.missing", UiLanguage.EN, Map.of("count", 1)).isEmpty());
        assertEquals(UiLanguage.ZH_CN, I18n.current());
    }

    @Test
    void pseudoIsDerivedFromEnglish() {
        I18n.initialize(UiLanguage.PSEUDO);
        assertEquals("[Šáṽé ~~]", I18n.message("demo.save"));
    }

    @Test
    void switchToWithoutApplicationStillSwitches() {
        I18n.switchTo(UiLanguage.EN);
        assertEquals(UiLanguage.EN, I18n.current());
        assertEquals("Save", I18n.message("demo.save"));
    }

    @Test
    void switchToPublishesOnCallingThreadOnlyWhenLanguageChanges(@TempDir Path home) {
        PathManager.setHomePathForTests(home);
        TestApplication app = new TestApplication();
        try {
            app.init();
            List<String> events = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            app.getMessageBus().connect().subscribe(LocaleListener.class, (oldLanguage, newLanguage) -> {
                events.add(oldLanguage + "->" + newLanguage);
                threads.add(Thread.currentThread());
            });

            I18n.switchTo(UiLanguage.EN);
            I18n.switchTo(UiLanguage.EN);

            assertEquals(List.of("ZH_CN->EN"), events);
            assertEquals(List.of(Thread.currentThread()), threads);
        } finally {
            app.shutdown();
            Disposer.clearAll();
            PathManager.resetForTests();
        }
    }

    @Test
    void localizedExceptionFormatsInCurrentLanguage() {
        I18n.initialize(UiLanguage.EN);
        LocalizedException error = new LocalizedException("demo.count", Map.of("count", 1), "archive failed");
        assertEquals("1 item archived", error.localizedMessage());
        assertEquals("archive failed", error.getMessage());
        assertEquals(1, error.params().get("count"));
    }

    @Test
    void localizedExceptionStoresNullParamsAsEmptyText() {
        Map<String, Object> params = new HashMap<>();
        params.put("count", null);
        params.put(null, "unnamed");
        LocalizedException error = new LocalizedException("demo.count", params, "archive failed");
        assertEquals(Map.of("count", ""), error.params());
        assertEquals("已归档  条", error.localizedMessage());
    }

    @Test
    void resetForTestsRestoresJvmDefaultLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(new Locale("xx", "XX"));
        I18n.resetForTests();
        assertEquals(before, Locale.getDefault());
    }
}
