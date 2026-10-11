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

    private TextSanitizer() {
    }

    /** {@code raw} with every disallowed character removed and whitespace collapsed (never null). */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), 4096));
        boolean pendingSpace = false;
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
                continue;
            }
            if (cp == '{' || cp == '}' || !allowed(cp)) {
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
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
