package com.jdesk.frame.vfs;

import org.junit.jupiter.api.Test;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sort key bytes must order like the Collator. SQLite compares BLOBs with memcmp, so the tests use
 * {@link Arrays#compareUnsigned(byte[], byte[])} to mimic it.
 */
class VfsFileNameSortTest {

    private static final List<String> NAMES =
            List.of("苹果.md", "阿里.md", "啊哈.md", "中国.md", "北京.md", "Apple.md", "banana.md");

    @Test
    void sortKeyBytesOrderMatchesCollatorOrder() {
        Collator collator = VfsFileNameSort.collatorFor(Locale.CHINA);

        List<String> byCollator = new ArrayList<>(NAMES);
        byCollator.sort(collator);

        List<String> byKeyBytes = new ArrayList<>(NAMES);
        byKeyBytes.sort(Comparator.comparing(n -> VfsFileNameSort.key(collator, n), Arrays::compareUnsigned));

        assertEquals(byCollator, byKeyBytes);
    }

    @Test
    void chineseNamesSortByPinyinNotCodePoint() {
        Collator collator = VfsFileNameSort.collatorFor(Locale.CHINA);
        List<String> sorted = new ArrayList<>(NAMES);
        sorted.sort(Comparator.comparing(n -> VfsFileNameSort.key(collator, n), Arrays::compareUnsigned));

        // Ordered by pinyin initials (a, a, b, p, z); code point order would differ.
        assertEquals(List.of("Apple.md", "banana.md", "啊哈.md", "阿里.md", "北京.md", "苹果.md", "中国.md"), sorted);
    }

    @Test
    void nullLocaleFallsBackToDefaultAndNullNameIsEmpty() {
        Collator collator = VfsFileNameSort.collatorFor(null);
        assertEquals(0, Arrays.compareUnsigned(
                VfsFileNameSort.key(collator, null), VfsFileNameSort.key(collator, "")));
    }
}
