package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** D4: the author-text sanitiser (D3). */
class TextSanitizerTest {

    @Test
    void stripsEveryFormattingCode() {
        assertEquals("Red bold", TextSanitizer.clean("§cRed §lbold§r"));
        assertEquals("", TextSanitizer.clean("§"));
    }

    @Test
    void stripsControlFormatAndBidiCharacters() {
        assertEquals("ab", TextSanitizer.clean("a\u0000\u0007b"));
        assertEquals("ab", TextSanitizer.clean("a​b"));
        assertEquals("ab", TextSanitizer.clean("a‮b"));
        assertEquals("ab", TextSanitizer.clean("ab"));
    }

    @Test
    void noJsonComponentSurvives() {
        String cleaned = TextSanitizer.clean("{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\"}}");
        assertFalse(cleaned.contains("{"));
        assertFalse(cleaned.contains("}"));
    }

    @Test
    void collapsesWhitespaceAndTrims() {
        assertEquals("a b c", TextSanitizer.clean("  a \n\t b\r\n  c  "));
    }

    @Test
    void limitsCountCodePoints() {
        String sixtyFour = "x".repeat(64);
        assertTrue(TextSanitizer.cleanWithin(sixtyFour, TextSanitizer.TITLE_MAX).isPresent());
        assertTrue(TextSanitizer.cleanWithin(sixtyFour + "x", TextSanitizer.TITLE_MAX).isEmpty());
        // Formatting codes do not count: they are gone before the length check.
        assertTrue(TextSanitizer.cleanWithin("§a" + sixtyFour, TextSanitizer.TITLE_MAX).isPresent());
        String emoji = "😀".repeat(64);
        assertTrue(TextSanitizer.cleanWithin(emoji, TextSanitizer.TITLE_MAX).isPresent());
    }

    @Test
    void cleanIsIdempotent() {
        String once = TextSanitizer.clean(" §kHi {there}\u0001 ");
        assertEquals(once, TextSanitizer.clean(once));
        assertTrue(TextSanitizer.isClean(once));
        assertFalse(TextSanitizer.isClean("§cx"));
    }
}
