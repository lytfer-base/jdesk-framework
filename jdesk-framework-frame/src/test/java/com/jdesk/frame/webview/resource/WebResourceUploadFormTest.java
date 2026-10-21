package com.jdesk.frame.webview.resource;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WebResourceUploadFormTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String TOKEN = "handoff-token";
    private static final List<WebResourceUploadFile> FILES = List.of(
            new WebResourceUploadFile("一品.txt", "C:\\Users\\a b\\一品.txt"),
            new WebResourceUploadFile("b.md", "D:\\notes\\b.md"));

    private static String listed(List<WebResourceUploadFile> files) {
        return WebResourceUploadForm.FIELD + "=" + URLEncoder.encode(WebResourceUploadForm.encode(files), StandardCharsets.UTF_8);
    }

    private static String token(String value) {
        return WebResourceUploadForm.TOKEN_FIELD + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<WebResourceUploadFile> decode(String form) {
        return WebResourceUploadForm.decode(FORM, form.getBytes(StandardCharsets.UTF_8), TOKEN);
    }

    @Test
    void encodeDecodeRoundTripKeepsWindowsPathsAndUnicode() {
        assertEquals(FILES, decode("note=hello&" + listed(FILES) + "&" + token(TOKEN)));
        assertEquals(FILES, decode(token(TOKEN) + "&" + listed(FILES)), "field order doesn't matter");
    }

    /** A path list built by page script yields nothing without the right token, whether missing, wrong or empty. */
    @Test
    void aListWithoutTheRightTokenYieldsNoFiles() {
        assertEquals(List.of(), decode(listed(FILES)));
        assertEquals(List.of(), decode(listed(FILES) + "&" + token("guess")));
        assertEquals(List.of(), decode(listed(FILES) + "&" + token("")));
        assertEquals(List.of(), WebResourceUploadForm.decode(FORM,
                (listed(FILES) + "&" + token(TOKEN)).getBytes(StandardCharsets.UTF_8), null), "no server token");
        assertEquals(List.of(), WebResourceUploadForm.decode(FORM,
                (listed(FILES) + "&" + token("")).getBytes(StandardCharsets.UTF_8), ""));
    }

    /** A field given twice yields nothing, since there's no telling which one the host wrote. */
    @Test
    void repeatedFieldsYieldNoFiles() {
        List<WebResourceUploadFile> forged = List.of(new WebResourceUploadFile("x.png", "C:\\secret.png"));

        assertEquals(List.of(), decode(listed(forged) + "&" + listed(FILES) + "&" + token(TOKEN)));
        assertEquals(List.of(), decode(listed(FILES) + "&" + token(TOKEN) + "&" + token(TOKEN)));
    }

    @Test
    void otherContentTypesAndMissingFieldYieldNoFiles() {
        assertEquals(List.of(), WebResourceUploadForm.decode("multipart/form-data; boundary=x", new byte[] {1}, TOKEN));
        assertEquals(List.of(), decode("a=1&" + token(TOKEN)));
        assertEquals(List.of(), WebResourceUploadForm.decode(null, new byte[0], TOKEN));
    }

    @Test
    void malformedJsonOrPercentEncodingYieldsNoFiles() {
        assertEquals(List.of(), decode(WebResourceUploadForm.FIELD + "=%5B%7B&" + token(TOKEN)));
        assertEquals(List.of(), decode(WebResourceUploadForm.FIELD + "=%7B%7D&" + token(TOKEN)), "not an array");
        assertEquals(List.of(), decode("a%=1&" + listed(FILES) + "&" + token(TOKEN)), "bad escapes must not throw");
    }

    @Test
    void withoutTokenDropsOnlyTheTokenField() {
        String rest = "note=hello&" + listed(FILES);
        byte[] stripped = WebResourceUploadForm.withoutToken((rest + "&" + token(TOKEN)).getBytes(StandardCharsets.UTF_8));

        assertEquals(rest, new String(stripped, StandardCharsets.UTF_8));
        byte[] untouched = rest.getBytes(StandardCharsets.UTF_8);
        assertSame(untouched, WebResourceUploadForm.withoutToken(untouched), "returned as is without a token");
        assertArrayEquals("a=1".getBytes(StandardCharsets.UTF_8), WebResourceUploadForm.withoutToken(
                (token(TOKEN) + "&a=1").getBytes(StandardCharsets.UTF_8)));
    }
}
