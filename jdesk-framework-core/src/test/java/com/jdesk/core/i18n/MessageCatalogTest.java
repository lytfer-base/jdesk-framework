package com.jdesk.core.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageCatalogTest {
    private static final String BROKEN_JA = "i18n-test/broken/broken_ja.properties";

    @Test
    void malformedCatalogFallsBackAndIsReadOnlyOnce() {
        CountingLoader loader = new CountingLoader(getClass().getClassLoader(), BROKEN_JA);
        MessageCatalog catalog = new MessageCatalog();
        catalog.registerRoot("i18n-test/broken", loader, UiLanguage.ZH_CN);

        assertEquals("Hello", catalog.find("broken.greeting", UiLanguage.JA));
        assertEquals("Hello", catalog.find("broken.greeting", UiLanguage.JA));
        assertEquals("你好", catalog.find("broken.greeting", UiLanguage.ZH_CN));
        assertEquals(1, loader.opens.get(), "a broken file is cached as empty, so it is read and warned about once");
    }

    /** Counts how often one resource is opened and otherwise delegates to the parent loader. */
    private static final class CountingLoader extends ClassLoader {
        private final String counted;
        private final AtomicInteger opens = new AtomicInteger();

        CountingLoader(ClassLoader parent, String counted) {
            super(parent);
            this.counted = counted;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (counted.equals(name)) {
                opens.incrementAndGet();
            }
            return super.getResourceAsStream(name);
        }
    }
}
