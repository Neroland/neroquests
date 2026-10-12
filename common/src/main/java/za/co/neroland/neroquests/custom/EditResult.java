package za.co.neroland.neroquests.custom;

import java.util.List;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The outcome of one editor mutation (D2): success with the id it concerned, or a refusal as a translation
 * key plus plain arguments (resource ids and numbers — never author text, never a player). The editor UI
 * shows {@link #message()}; commands send it as their failure.
 *
 * @param ok   whether the mutation was applied
 * @param id   the quest or chapter it concerned (the new id for a create), when there is one
 * @param key  the translation key of the refusal ({@code null} on success)
 * @param args the refusal's arguments
 */
public record EditResult(boolean ok, Optional<Identifier> id, String key, List<String> args) {

    private static final String P = "message.neroquests.editor.";

    // --- refusal keys (all in en_us.json) -------------------------------------

    public static final String DENIED = P + "denied";
    public static final String RATE_LIMITED = P + "rate_limited";
    public static final String UNKNOWN_CHAPTER = P + "unknown_chapter";
    public static final String UNKNOWN_QUEST = P + "unknown_quest";
    public static final String CAP_QUESTS = P + "cap.quests";
    public static final String CAP_CHAPTERS = P + "cap.chapters";
    public static final String CAP_OBJECTIVES = P + "cap.objectives";
    public static final String CAP_REWARDS = P + "cap.rewards";
    public static final String CAP_STACKS = P + "cap.stacks";
    public static final String CAP_XP = P + "cap.xp";
    public static final String CAP_CURRENCY = P + "cap.currency";
    public static final String TITLE_EMPTY = P + "title.empty";
    public static final String TEXT_TOO_LONG = P + "text.too_long";
    public static final String ICON_UNKNOWN = P + "icon.unknown";
    public static final String COORDINATES = P + "coordinates";
    public static final String CYCLE = P + "cycle";
    public static final String SELF_PREREQUISITE = P + "prerequisite.self";
    public static final String PREREQUISITE_UNKNOWN = P + "prerequisite.unknown";
    public static final String OBJECTIVE_NONE = P + "objective.none";
    public static final String OBJECTIVE_TYPE = P + "objective.type";
    public static final String OBJECTIVE_COUNT = P + "objective.count";
    public static final String OBJECTIVE_TARGET = P + "objective.target";
    public static final String OBJECTIVE_LABEL = P + "objective.label";
    public static final String REWARD_TYPE = P + "reward.type";
    public static final String REWARD_ITEM = P + "reward.item";
    public static final String REWARD_STACK = P + "reward.stack";
    public static final String REWARD_LOOT_TABLE = P + "reward.loot_table";
    public static final String REWARD_CURRENCY = P + "reward.currency";
    /** J2 (RT-04): a loot table outside the {@code customLootTablePrefixes}. */
    public static final String REWARD_LOOT_PREFIX = P + "reward.loot_prefix";
    /** J2 (RT-03): only an operator or an allow-listed author may set credits or edit a quest that pays them. */
    public static final String CURRENCY_DENIED = P + "currency_denied";
    public static final String ESCROW_LOOT_TABLE = P + "escrow.loot_table";
    public static final String ESCROW_MISSING = P + "escrow.missing";
    public static final String ESCROW_COPIES = P + "escrow.copies";
    public static final String ESCROW_NO_PLAYER = P + "escrow.no_player";
    public static final String COOLDOWN = P + "cooldown";
    public static final String REFERENCED = P + "referenced";
    public static final String INVALID = P + "invalid";
    public static final String BAD_REQUEST = P + "bad_request";

    /** A success concerning {@code id}. */
    public static EditResult ok(Identifier id) {
        return new EditResult(true, Optional.ofNullable(id), null, List.of());
    }

    /** A refusal. */
    public static EditResult error(String key, String... args) {
        return new EditResult(false, Optional.empty(), key, List.of(args));
    }

    /** The refusal (or a generic "saved") as a translatable component. */
    public Component message() {
        if (ok) {
            return Component.translatable(P + "saved");
        }
        return Component.translatable(key, args.toArray());
    }
}
