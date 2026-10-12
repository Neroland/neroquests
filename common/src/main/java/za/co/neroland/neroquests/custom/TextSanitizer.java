package za.co.neroland.neroquests.custom;

import java.util.Optional;

/**
 * Cleans author-written text — custom quest and chapter titles, descriptions and {@code manual} labels —
 * before it is stored (D3). Pure, so the store, the importer and the tests apply exactly the same rule.
 *
 * <p>What is removed:
 * <ul>
 *   <li>legacy formatting codes: {@code §} together with the character after it (all of them — there is
 *       no allow-list, so nobody can colour, obfuscate or fake a system message);</li>
 *   <li>control, format (zero-width, bidi overrides), private-use, surrogate and unassigned code points;</li>
 *   <li>characters that render as nothing although Unicode files them as letters or symbols (J2, RT-13): the
 *       Hangul fillers U+115F, U+1160, U+3164 and U+FFA0, the braille blank U+2800, the combining grapheme joiner
 *       U+034F, the Khmer inherent vowels U+17B4/U+17B5, variation selectors and U+FFFC;</li>
 *   <li>combining marks beyond {@value #MAX_MARKS_PER_BASE} on one base character (no "Zalgo" towers), and any
 *       combining mark with no base character before it;</li>
 *   <li>curly braces (<code>&#123;</code> and <code>&#125;</code>), so nothing resembling a JSON text component
 *       survives;</li>
 *   <li>runs of whitespace (including line breaks), collapsed to one space and trimmed.</li>
 * </ul>
 *
 * <p>The result is a plain string. It is rendered with {@code Component.literal} only (see
 * {@link za.co.neroland.neroquests.quest.QuestText}) — never parsed as a component, never used as a
 * translation key — so even text that slipped past this filter could not click, run a command, or
 * pull in another translation. Author text is never logged and never sent to telemetry.
 */
public final class TextSanitizer {

    /** Longest title, in code points. */
    public static final int TITLE_MAX = 64;

    /** Longest description, in code points. */
    public static final int DESCRIPTION_MAX = 512;

    /** Longest {@code manual} objective label, in code points. */
    public static final int LABEL_MAX = 64;

    private static final int SECTION_SIGN = 0x00A7;

    /** Most combining marks kept on one base character. */
    public static final int MAX_MARKS_PER_BASE = 2;

    private TextSanitizer() {
    }

    /** {@code raw} with every disallowed character removed and whitespace collapsed (never null). */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), 4096));
        boolean pendingSpace = false;
        int marks = -1; // combining marks on the current base character; -1 = no base yet
        int index = 0;
        while (index < raw.length()) {
            int cp = raw.codePointAt(index);
            index += Character.charCount(cp);
            if (cp == SECTION_SIGN) {
                if (index < raw.length()) {
                    index += Character.charCount(raw.codePointAt(index)); // the code's own character
                }
                continue;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0;
                marks = -1;
                continue;
            }
            if (cp == '{' || cp == '}' || !allowed(cp) || invisible(cp)) {
                continue;
            }
            if (isMark(cp)) {
                if (marks < 0 || marks >= MAX_MARKS_PER_BASE || pendingSpace) {
                    continue; // no base to sit on, or already enough on this one
                }
                marks++;
                out.appendCodePoint(cp);
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
            marks = 0;
        }
        return out.toString();
    }

    /** Whether {@code text} is already exactly what {@link #clean} would make of it. */
    public static boolean isClean(String text) {
        return text != null && clean(text).equals(text);
    }

    /** Length in code points (what the limits count). */
    public static int length(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /**
     * Clean {@code raw} and check it against {@code max} code points.
     *
     * @return the cleaned text, or empty when it is longer than {@code max} after cleaning
     */
    public static Optional<String> cleanWithin(String raw, int max) {
        String cleaned = clean(raw);
        return length(cleaned) <= max ? Optional.of(cleaned) : Optional.empty();
    }

    /**
     * Whether {@code text} holds at least one character that is drawn: a letter, digit, symbol or punctuation
     * mark (fillers are already gone after {@link #clean}). A title or label without one is refused as empty.
     */
    public static boolean hasVisible(String text) {
        if (text == null) {
            return false;
        }
        int index = 0;
        while (index < text.length()) {
            int cp = text.codePointAt(index);
            index += Character.charCount(cp);
            if (invisible(cp) || isMark(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                continue;
            }
            switch (Character.getType(cp)) {
                case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                        Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER,
                        Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.MATH_SYMBOL,
                        Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
                        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                        Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> {
                    return true;
                }
                default -> {
                    // keep looking
                }
            }
        }
        return false;
    }

    /** Code points that render as nothing (or as a blank) although they are not format characters. */
    static boolean invisible(int cp) {
        return cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800 || cp == 0x034F
                || cp == 0x17B4 || cp == 0x17B5 || cp == 0xFFFC
                || (cp >= 0x180B && cp <= 0x180F) || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF);
    }

    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    private static boolean allowed(int cp) {
        switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE,
                    Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> {
                return false;
            }
            default -> {
                return true;
            }
        }
    }
}
