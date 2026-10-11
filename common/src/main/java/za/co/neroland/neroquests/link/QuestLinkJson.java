package za.co.neroland.neroquests.link;

import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.compat.neroeconomy.EconomyCompat;
import za.co.neroland.neroquests.data.OwedReward;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.QuestSource;

/**
 * The pure JSON builders behind link schema 2 (G1): the identity fields every quest and chapter row
 * carries, owed entries, the {@code custom_authoring} body and objective summaries. No server, no
 * registry — unit-tested directly.
 *
 * <p><b>Text rule.</b> Spine and pack text is a translation key: it goes out under {@code title} /
 * {@code description} (as in schema 1) <em>and</em>, when the key resolves in the bundled English
 * language file, as plain English under {@code title_text} / {@code description_text}. Custom text is
 * what a player typed: it goes out <em>only</em> as {@code title_text} / {@code description_text}, never
 * under the key fields (a client must not try to translate it) and never in an alert.
 *
 * <p><b>Privacy (POPIA/GDPR).</b> Nothing built here holds a UUID or a name. An owed entry's
 * {@code key} is {@code nq:<sequence>:<source id>} (or a {@code nq:i:}/{@code nq:x:} item/XP variant) —
 * no player identifier.
 */
public final class QuestLinkJson {

    private QuestLinkJson() {
    }

    /** {@code "spine"}, {@code "pack"} or {@code "custom"}. */
    public static String sourceName(QuestSource source) {
        return source.wireName();
    }

    /**
     * Add the schema-2 identity and text fields to a quest row.
     *
     * @param english    translation key &rarr; English text, or {@code null} when the key is unknown
     * @param chapter    the chapter that lists the quest, or {@code null}
     * @param modPresent false only for a pack quest shown with the "mod not installed" badge
     */
    public static void questIdentity(JsonObject row, Identifier id, QuestSource source, String title,
                                     String description, Identifier chapter, boolean modPresent,
                                     Function<String, String> english) {
        row.addProperty("source", sourceName(source));
        row.addProperty("namespace", id.getNamespace());
        if (chapter != null) {
            row.addProperty("chapter", chapter.toString());
        }
        row.addProperty("mod_present", modPresent);
        text(row, source, "title", title, english);
        if (description != null && !description.isEmpty()) {
            text(row, source, "description", description, english);
        }
    }

    /** {@code key} (+ {@code key_text}) for spine/pack, {@code key_text} alone for custom. */
    static void text(JsonObject row, QuestSource source, String field, String value,
                     Function<String, String> english) {
        if (source == QuestSource.CUSTOM) {
            row.addProperty(field + "_text", value);
            return;
        }
        row.addProperty(field, value);
        String resolved = english == null ? null : english.apply(value);
        if (resolved != null && !resolved.isEmpty()) {
            row.addProperty(field + "_text", resolved);
        }
    }

    /**
     * One {@code chapters} row: {@code {id, title?, title_text?, icon, source, namespace, mod_present,
     * order, quests[]}}.
     */
    public static JsonObject chapterRow(Chapter chapter, int order, JsonArray questIds,
                                        Function<String, String> english) {
        JsonObject row = new JsonObject();
        row.addProperty("id", chapter.id().toString());
        QuestSource source = chapter.source();
        text(row, source, "title", chapter.title(), english);
        row.addProperty("icon", chapter.icon().toString());
        row.addProperty("source", sourceName(source));
        row.addProperty("namespace", chapter.id().getNamespace());
        row.addProperty("mod_present", !chapter.modAbsent());
        row.addProperty("order", order);
        row.add("quests", questIds);
        return row;
    }

    /**
     * One owed entry as the app's claim list shows it: {@code {key, type, amount | item+count,
     * currency?, quest?, reason, created_at, permanent}}. {@code quest} is present for quest rewards
     * (absent for daily contracts).
     */
    public static JsonObject owedRow(OwedReward entry) {
        JsonObject row = new JsonObject();
        row.addProperty("key", entry.requestKey());
        row.addProperty("type", entry.kind().serialName());
        if (entry.kind() == OwedReward.Kind.ITEM) {
            row.addProperty("item", entry.item().toString());
            row.addProperty("count", entry.amount());
        } else {
            if (entry.isCurrency()) {
                row.addProperty("currency", entry.currency().toString());
            }
            row.addProperty("amount", entry.amount());
        }
        if (!EconomyCompat.REASON_CONTRACT.equals(entry.reason())) {
            row.addProperty("quest", entry.source().toString());
        }
        row.addProperty("reason", entry.reason());
        row.addProperty("created_at", entry.createdAt());
        row.addProperty("permanent", entry.permanent());
        return row;
    }

    /** {@code {schema_version, owed[]}} for the given entries. */
    public static JsonObject owedSection(Iterable<OwedReward> entries) {
        JsonArray rows = new JsonArray();
        for (OwedReward entry : entries) {
            rows.add(owedRow(entry));
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", QuestLinkModule.SCHEMA_VERSION);
        root.addProperty("count", rows.size());
        root.add("owed", rows);
        return root;
    }

    /** {@code {schema_version, can_author}} — a boolean only: no lock state, no allow-list, no names. */
    public static JsonObject customAuthoring(boolean canAuthor) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", QuestLinkModule.SCHEMA_VERSION);
        root.addProperty("can_author", canAuthor);
        return root;
    }

    /**
     * A short English summary of one objective, e.g. {@code "Collect 16 × #minecraft:logs"}. Built from
     * the type and the content label only — never from player-written text.
     */
    public static String objectiveText(Identifier type, int target, String content) {
        String verb = switch (type.getPath()) {
            case "collect_item" -> "Collect";
            case "craft_item" -> "Craft";
            case "kill_entity" -> "Defeat";
            case "reach_dimension" -> "Reach";
            case "visit_structure" -> "Visit";
            case "quest_complete" -> "Complete";
            case "gate_open" -> "Reach milestone";
            case "custom_event" -> "Event";
            case "economy" -> "Economy";
            case "manual" -> "Confirmed by an editor";
            default -> type.getPath().replace('_', ' ');
        };
        if ("manual".equals(type.getPath())) {
            return verb;
        }
        StringBuilder out = new StringBuilder(verb);
        if (target > 1) {
            out.append(' ').append(target).append(" ×");
        }
        if (content != null && !content.isEmpty()) {
            out.append(' ').append(content);
        }
        return out.toString();
    }
}
