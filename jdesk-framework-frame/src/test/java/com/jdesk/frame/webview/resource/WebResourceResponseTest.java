package com.jdesk.frame.webview.resource;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebResourceResponseTest {

    @Test
    void fileResponseCarriesPathNotBytes() {
        Path file = Path.of("D:/x/a.png");
        WebResourceResponse response = WebResourceResponse.ofFile("image/png", file);

        assertEquals(200, response.statusCode());
        assertEquals(Optional.of(file), response.file());
        assertEquals("image/png", response.headers().get("Content-Type"));
        assertEquals("no-cache", response.headers().get("Cache-Control"));
        assertArrayEquals(new byte[0], response.body());
    }

    @Test
    void withHeaderKeepsFileAndWithoutBodyDropsIt() {
        WebResourceResponse response = WebResourceResponse.ofFile(200, Map.of("Content-Type", "text/plain"), Path.of("a.txt"));

        assertTrue(response.withHeader("X-A", "1").file().isPresent());
        assertTrue(response.withoutBody().file().isEmpty());
    }

    @Test
    void byteResponseHasNoFile() {
        assertTrue(WebResourceResponse.ok("text/plain", new byte[] {1}).file().isEmpty());
    }
}
