package com.jdesk.frame.webview.resource;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebResourceRouteTest {

    @Test
    void mapsNameHostAndRestIntoHandlerUri() {
        WebResourceRoute route = WebResourceRoute.parse("/file/record/images/a.png", "v=12").orElseThrow();

        assertEquals("file", route.name());
        assertEquals("record", route.host());
        assertEquals("file://record/images/a.png?v=12", route.uri().toString());
        assertEquals("/images/a.png", route.uri().getPath());
    }

    @Test
    void keepsPercentEncodingForHandlerToDecode() {
        WebResourceRoute route = WebResourceRoute.parse("/file/prj-1/%E7%85%A7%E7%89%87/%E5%9B%BE1.jpg", null).orElseThrow();

        assertEquals("/照片/图1.jpg", route.uri().getPath());
    }

    @Test
    void trailingSlashAndEmptyRestAreAllowed() {
        assertEquals("file://record/images/", WebResourceRoute.parse("/file/record/images/", null).orElseThrow().uri().toString());
        assertEquals("file://prj-1/", WebResourceRoute.parse("/file/prj-1/", null).orElseThrow().uri().toString());
        assertEquals("asset://thumb/f0?size=200", WebResourceRoute.parse("/asset/thumb/f0", "size=200").orElseThrow().uri().toString());
    }

    @Test
    void rootAndSingleSegmentAreNotRoutes() {
        assertEquals(Optional.empty(), WebResourceRoute.parse("/", null));
        assertEquals(Optional.empty(), WebResourceRoute.parse("/file", null));
        assertEquals(Optional.empty(), WebResourceRoute.parse("/file/", null));
        assertEquals(Optional.empty(), WebResourceRoute.parse("/File/record/a", null));
    }

    @Test
    void rejectsTraversalBackslashEmptyMiddleSegmentAndControlChars() {
        assertThrows(IllegalArgumentException.class, () -> WebResourceRoute.parse("/file/record/../x", null));
        assertThrows(IllegalArgumentException.class, () -> WebResourceRoute.parse("/file/record/%2e%2e/x", null));
        assertThrows(IllegalArgumentException.class, () -> WebResourceRoute.parse("/file/record/a%5Cb", null));
        assertThrows(IllegalArgumentException.class, () -> WebResourceRoute.parse("/file/record//x", null));
        assertThrows(IllegalArgumentException.class, () -> WebResourceRoute.parse("/file/record/a%00b", null));
        assertThrows(IllegalArgumentException.class, () -> WebResourceRoute.parse("/file/record/a%2Fb", null));
        assertThrows(IllegalArgumentException.class, () -> WebResourceRoute.parse("/file/record/%zz", null));
    }

    @Test
    void mapsFetchDestToResourceType() {
        assertEquals(WebResourceType.MAIN_FRAME, WebResourceRoute.resourceTypeOf("document"));
        assertEquals(WebResourceType.SUB_FRAME, WebResourceRoute.resourceTypeOf("iframe"));
        assertEquals(WebResourceType.SCRIPT, WebResourceRoute.resourceTypeOf("worker"));
        assertEquals(WebResourceType.STYLESHEET, WebResourceRoute.resourceTypeOf("style"));
        assertEquals(WebResourceType.IMAGE, WebResourceRoute.resourceTypeOf("image"));
        assertEquals(WebResourceType.FONT, WebResourceRoute.resourceTypeOf("font"));
        assertEquals(WebResourceType.MEDIA, WebResourceRoute.resourceTypeOf("video"));
        assertEquals(WebResourceType.XHR, WebResourceRoute.resourceTypeOf("empty"));
        assertEquals(WebResourceType.OTHER, WebResourceRoute.resourceTypeOf(null));
        assertEquals(WebResourceType.OTHER, WebResourceRoute.resourceTypeOf("manifest"));
    }
}
