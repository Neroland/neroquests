package za.co.neroland.neroquests.quest;

import net.minecraft.network.chat.Component;

import za.co.neroland.neroquests.quest.objective.ManualObjective;

/**
 * How a definition's own words become a {@link Component} — the one place that decides (D3).
 *
 * <p>Spine and pack titles, descriptions and {@code manual} labels are translation keys, rendered with
 * {@code Component.translatable}. Custom (editor) quests and chapters carry <b>author-written plain text</b>
 * instead, and that text is rendered with {@code Component.literal} — <em>only</em> these fields, and only
 * for {@link QuestSource#CUSTOM}. Why: a literal component is inert. Author text is therefore never looked up
 * as a translation key (it could otherwise name and display any key in any loaded language file), never
 * parsed as a JSON component (no click or hover events, no commands, no selectors or score lookups) and
 * keeps exactly the characters the sanitiser let through. Pure, usable on both sides.
 */
public final class QuestText {

    private QuestText() {
    }

    /** A quest's title. */
    public static Component title(Quest quest) {
        return text(quest.source(), quest.title());
    }

    /** A quest's description (may be empty). */
    public static Component description(Quest quest) {
        return text(quest.source(), quest.description());
    }

    /** A chapter's title. */
    public static Component title(Chapter chapter) {
        return text(chapter.source(), chapter.title());
    }

    /** A {@code manual} objective's label, as written in {@code owner}. */
    public static Component manualLabel(QuestSource owner, ManualObjective manual) {
        return text(owner, manual.label());
    }

    private static Component text(QuestSource source, String value) {
        return source == QuestSource.CUSTOM ? Component.literal(value) : Component.translatable(value);
    }
}
