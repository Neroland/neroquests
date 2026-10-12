package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** J2 (RT-13): fillers, blank-rendering letters and combining-mark towers. */
class SanitizerFillerTest {

    @Test
    void stripsFillersThatUnicodeFilesAsLetters() {
        assertEquals("ab", TextSanitizer.clean("a\u3164b"));
        assertEquals("ab", TextSanitizer.clean("a\u115F\u1160b"));
        assertEquals("ab", TextSanitizer.clean("a\uFFA0b"));
        assertEquals("ab", TextSanitizer.clean("a\u2800b"));
        assertEquals("ab", TextSanitizer.clean("a\u034Fb"));
        assertEquals("ab", TextSanitizer.clean("a\uFE0Fb"));
        assertEquals("ab", TextSanitizer.clean("a\u17B4\u17B5b"));
    }

    @Test
    void aFillerOnlyTitleIsBlank() {
        String cleaned = TextSanitizer.clean("\u3164\u3164\u2800\u115F");
        assertEquals("", cleaned);
        assertFalse(TextSanitizer.hasVisible(cleaned));
        assertFalse(TextSanitizer.hasVisible("\u0301\u0301"));
        assertTrue(TextSanitizer.hasVisible("."), "punctuation is drawn");
        assertTrue(TextSanitizer.hasVisible("\u4E2D"), "CJK");
        assertTrue(TextSanitizer.hasVisible("\uD83D\uDE00"), "emoji (So)");
    }

    @Test
    void combiningMarksAreCappedPerBaseCharacter() {
        String zalgo = "Z" + "\u0301".repeat(30) + "a" + "\u0300".repeat(5);
        assertEquals("Z\u0301\u0301a\u0300\u0300", TextSanitizer.clean(zalgo));
        assertEquals("e\u0301", TextSanitizer.clean("e\u0301"), "ordinary accents stay");
        assertEquals("x", TextSanitizer.clean("\u0301\u0301x"), "a mark with no base goes");
        assertEquals("a b", TextSanitizer.clean("a \u0301b"), "a mark after a space has no base");
    }

    @Test
    void cleaningIsIdempotent() {
        String raw = "  \u00a7cT\u0301\u0301\u0301itle\u3164 \u2800 {x}  ";
        String once = TextSanitizer.clean(raw);
        assertEquals(once, TextSanitizer.clean(once));
        assertTrue(TextSanitizer.isClean(once));
    }
}
