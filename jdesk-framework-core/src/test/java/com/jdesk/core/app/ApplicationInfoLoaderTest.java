package com.jdesk.core.app;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationInfoLoaderTest {

    @Test
    void missingDescriptorUsesDefaultProductInfo() {
        ApplicationInfo info = ApplicationInfoLoader.load(new ClassLoader(null) {
        });

        assertEquals("JDesk Application", info.getProductName());
        assertEquals("1.0", info.getFullVersion());
    }

    @Test
    void descriptorLoadsNamesVersionCompanyAndIcons() {
        ClassLoader loader = resourceLoader("""
                <application-info id="demo-app">
                    <names product="Demo" fullName="Demo Application"/>
                    <version major="2" minor="5" micro="1" suffix="EAP" build="251.1"/>
                    <company name="JDesk" url="https://example.test"/>
                    <description>Loaded from test descriptor.</description>
                    <icon svg="/icons/app.svg" svg-small="/icons/app-small.svg"/>
                </application-info>
                """);

        ApplicationInfo info = ApplicationInfoLoader.load(loader);

        assertEquals("demo-app", info.getId());
        assertEquals("Demo", info.getProductName());
        assertEquals("Demo Application", info.getFullProductName());
        assertEquals("2.5.1 EAP", info.getFullVersion());
        assertEquals("2.5.1 EAP (251.1)", info.getDisplayVersion());
        assertEquals("JDesk", info.getCompanyName());
        assertEquals("https://example.test", info.getCompanyUrl());
        assertEquals("icons/app.svg", info.getIconSvgPath());
        assertEquals("icons/app-small.svg", info.getSmallIconSvgPath());
        assertTrue(info.getDescription().contains("test descriptor"));
    }

    @Test
    void versionDateBecomesBuildDate() {
        ClassLoader loader = resourceLoader("""
                <application-info id="demo-app">
                    <version major="2" minor="5" date="2026-09-07"/>
                </application-info>
                """);

        assertEquals("2026-09-07", ApplicationInfoLoader.load(loader).getBuildDate());
    }

    @Test
    void unresolvedBuildPlaceholderCountsAsNoDate() {
        ClassLoader loader = resourceLoader("""
                <application-info id="demo-app">
                    <version major="2" minor="5" date="${build.date}"/>
                </application-info>
                """);

        assertEquals("", ApplicationInfoLoader.load(loader).getBuildDate());
    }

    private static ClassLoader resourceLoader(String xml) {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (ApplicationInfo.DEFAULT_RESOURCE.equals(name)) {
                    return new ByteArrayInputStream(bytes);
                }
                return null;
            }
        };
    }
}
