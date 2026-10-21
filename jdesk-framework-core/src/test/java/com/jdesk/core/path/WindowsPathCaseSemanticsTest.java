package com.jdesk.core.path;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsPathCaseSemanticsTest {

    private final PathCaseSemantics semantics = PathCaseSemantics.current();

    @Test
    void currentReturnsTheWindowsImplementation() {
        assertSame(WindowsPathCaseSemantics.INSTANCE, PathCaseSemantics.current());
    }

    @Test
    void canonicalKeyFoldsCase() {
        assertEquals("docs/reports/a.txt", semantics.canonicalKey("Docs/Reports/A.txt"));
    }

    @Test
    void pathsDifferingOnlyInCaseAreTheSameName() {
        assertTrue(semantics.sameName("Docs/Reports", "docs/reports"));
    }

    /**
     * canonicalKey must fold with Locale.ROOT: Turkish toLowerCase() turns I into a dotless ı (U+0131). The data must
     * fold differently under Turkish, which the precondition checks; "Notes" would pass even without Locale.ROOT.
     */
    @Test
    void caseFoldingSurvivesTurkishDefaultLocale() {
        String name = "Inbox";
        Locale original = Locale.getDefault();
        Locale.setDefault(new Locale("tr"));
        try {
            assertNotEquals(name.toLowerCase(Locale.ROOT), name.toLowerCase(),
                    "precondition: the data must fold differently under Turkish and ROOT");
            assertAll(
                    () -> assertTrue(semantics.sameName(name, "inbox"), "Inbox vs inbox"),
                    () -> assertTrue(semantics.sameName("I", "i"), "I vs i"),
                    () -> assertEquals("images", semantics.canonicalKey("IMAGES"), "canonical form of IMAGES"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void canonicalKeyFoldsCaseOnly() {
        // The interface promises to fold case only; change its documentation before changing this behavior.
        assertAll(
                () -> assertFalse(semantics.sameName("a/b", "a\\b"), "separators are not normalized"),
                () -> assertFalse(semantics.sameName("x/", "x"), "a trailing slash is not normalized"),
                () -> assertFalse(semantics.sameName("a//b", "a/b"), "repeated slashes are not normalized"));
    }

    /**
     * The interface promises no Unicode normalization: NFC å (U+00E5) and NFD a + U+030A look identical but must keep
     * different canonical forms, so adding Normalizer.normalize(...) to canonicalKey fails this test.
     */
    @Test
    void unicodeNormalizationIsNotApplied() {
        // Keep these as escapes: as literals the two strings look identical in the source.
        String nfc = "\u00E5";
        String nfd = "a\u030A";
        assertFalse(semantics.sameName("dir/" + nfc, "dir/" + nfd), "NFC and NFD å are not unified");
    }

    @Test
    void dotSegmentsAreNotCollapsed() {
        // The interface leaves . and .. alone: they are path semantics, so normalize before calling.
        assertAll(
                () -> assertFalse(semantics.sameName("a/./b", "a/b"), ". segments are not collapsed"),
                () -> assertFalse(semantics.sameName("a/x/../b", "a/b"), ".. segments are not collapsed"));
    }

    /** Folding can map different strings to one key, so different disk names can collide as map keys. */
    @Test
    void differentDiskNamesCanCollideIntoOneKey() {
        // Keep the data as escapes: U+00C5 and U+212B look identical and would pass for a duplicate case.
        String aRing = "\u00C5";                 // Å  LATIN CAPITAL LETTER A WITH RING ABOVE
        String angstrom = "\u212B";              // Å  ANGSTROM SIGN
        String sharpSLower = "stra\u00DFe";      // straße
        String sharpSUpper = "STRA\u1E9E" + "E"; // STRAẞE, ẞ is U+1E9E, capital sharp s
        assertAll(
                // Preconditions: each pair really is two strings; otherwise the checks below compare a key with itself.
                () -> assertNotEquals(aRing, angstrom, "precondition: U+00C5 and U+212B are different strings"),
                () -> assertNotEquals(sharpSLower, sharpSUpper, "precondition: straße and STRAẞE are distinct"),
                () -> assertEquals(semantics.canonicalKey(aRing), semantics.canonicalKey(angstrom),
                        "U+00C5 and U+212B collide in one key"),
                () -> assertEquals(semantics.canonicalKey(sharpSLower), semantics.canonicalKey(sharpSUpper),
                        "straße and STRAẞE collide in one key"));
    }

    /**
     * Upper-casing makes ﬀ and FF equal, but the canonical form folds to lower case and keeps two keys. The
     * upper-case result is JDK behavior and serves only as a precondition here.
     */
    @Test
    void canonicalKeyFoldsTheLowerSideOnly() {
        String ffLigature = "\uFB00";            // ﬀ  ff ligature
        assertEquals(ffLigature.toUpperCase(Locale.ROOT), "FF".toUpperCase(Locale.ROOT),
                "precondition: ﬀ and FF upper-case alike, so the data shows which side is folded");
        assertNotEquals(semantics.canonicalKey(ffLigature), semantics.canonicalKey("FF"),
                "the canonical form folds down: ﬀ and FF are different keys");
    }

    /**
     * The canonical form's length can differ from the input's, so its indexes can't be used to cut the original:
     * İ (U+0130) folds to i + U+0307, one code point becoming two.
     */
    @Test
    void canonicalKeyLengthCanDifferFromInput() {
        String dotted = "\u0130";            // İ
        String dottedWord = "\u0130stanbul"; // İstanbul
        assertAll(
                // The claim itself; if İ ever folds to one code point, these fail and the javadoc warning is obsolete.
                () -> assertNotEquals(dotted.length(), semantics.canonicalKey(dotted).length(),
                        "İ: the canonical form has a different length"),
                () -> assertNotEquals(dottedWord.length(), semantics.canonicalKey(dottedWord).length(),
                        "İstanbul: the canonical form has a different length"),
                // The exact forms: if folding changes but the length still differs, only these fail.
                () -> assertEquals("i\u0307", semantics.canonicalKey(dotted), "canonical form of İ"),
                () -> assertEquals("i\u0307stanbul", semantics.canonicalKey(dottedWord), "canonical form of İstanbul"));
    }

    @Test
    void charactersWithoutCasePassThrough() {
        assertEquals("中文目录/docs", semantics.canonicalKey("中文目录/Docs"));
    }

    @Test
    void nullPathIsRejected() {
        assertThrows(NullPointerException.class, () -> semantics.canonicalKey(null));
    }
}
