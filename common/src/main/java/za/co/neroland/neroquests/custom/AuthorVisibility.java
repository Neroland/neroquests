package za.co.neroland.neroquests.custom;

import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestStates;

/**
 * Which quest ids an author may name in a custom quest — as a prerequisite or a {@code quest_complete}
 * target (§13, J1). Only quests that author can see: a loaded quest behind a closed {@code visible_gate} is
 * treated exactly like an id that does not exist, with the same refusal, so the editor can never be used to
 * probe for hidden quest ids. Custom quests in the store are always visible (they cannot carry a
 * {@code visible_gate}). Admin commands without a player see everything. Pure.
 */
public final class AuthorVisibility {

    /** The gate view of an admin command without a player: every gate counts as open. */
    public static final Predicate<Identifier> EVERYTHING = id -> true;

    private AuthorVisibility() {
    }

    /**
     * Whether {@code id} names a quest the author may reference.
     *
     * @param loaded   the running definitions (every source)
     * @param inStore  whether the previewed custom store holds the id
     * @param gateOpen the author's open gates ({@link #EVERYTHING} for the console)
     */
    public static boolean known(Identifier id, Map<Identifier, Quest> loaded, Predicate<Identifier> inStore,
                                Predicate<Identifier> gateOpen) {
        if (id == null) {
            return false;
        }
        Quest quest = loaded.get(id);
        if (quest != null) {
            return QuestStates.isVisible(quest, gateOpen);
        }
        return inStore.test(id);
    }
}
