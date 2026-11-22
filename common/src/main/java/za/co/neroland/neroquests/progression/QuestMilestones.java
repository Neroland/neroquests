package za.co.neroland.neroquests.progression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.engine.CompletionSignals;

/**
 * The personal quest milestones NeroQuests records as player-scope Neroland Core record gates, for trophies and
 * quest packs to read:
 *
 * <ul>
 *   <li>{@code neroquests:chapter/<namespace>/<path>} — every loaded quest of that chapter is complete for the
 *       player. Synthesised for every loaded chapter, sibling chapters included; NeroQuests' own three chapters
 *       are also declared as gate files;</li>
 *   <li>{@code neroquests:all_chapters} — every loaded chapter is complete for the player;</li>
 *   <li>{@code neroquests:completed/25} and {@code /100} — the player has completed that many quests;</li>
 *   <li>{@code neroquests:first_contract} — the player handed in their first daily contract.</li>
 * </ul>
 *
 * <p>Everything is derived from progress NeroQuests already stores (each player's completed-quest set); no
 * counter or new per-player record is kept. The rules run after every quest completion, so a player who
 * finished chapters before this change is credited on their next completion. Recording is idempotent.
 *
 * <p>Privacy (POPIA/GDPR): the player's UUID goes only into {@code ProgressionGates.open}, i.e. Core's
 * progression store, which Core's erasure already purges. Nothing here logs a name, UUID or position.
 */
public final class QuestMilestones {

    /** Completed-quest counts that open a {@code neroquests:completed/<n>} gate. */
    public static final int[] COMPLETED_RUNGS = {25, 100};

    /** {@code neroquests:all_chapters}. */
    public static final Identifier ALL_CHAPTERS = gate("all_chapters");

    /** {@code neroquests:first_contract}. */
    public static final Identifier FIRST_CONTRACT = gate("first_contract");

    private QuestMilestones() {
    }

    private static Identifier gate(String path) {
        return Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, path);
    }

    /** The record gate for completing {@code chapter}: {@code neroquests:chapter/<namespace>/<path>}. */
    public static Identifier chapterGate(Identifier chapter) {
        return gate("chapter/" + chapter.getNamespace() + "/" + chapter.getPath());
    }

    /** The {@code neroquests:completed/<n>} gate. */
    public static Identifier completedGate(int rung) {
        return gate("completed/" + rung);
    }

    /**
     * Every milestone gate a player has reached, given the loaded definitions and their progress. Pure, so it
     * runs in a plain JVM.
     */
    public static List<Identifier> reached(Map<Identifier, Chapter> chapters, Map<Identifier, Quest> quests,
            Set<Identifier> playerCompleted, Set<Identifier> serverCompleted) {
        List<Identifier> gates = new ArrayList<>();
        for (int rung : COMPLETED_RUNGS) {
            if (playerCompleted.size() >= rung) {
                gates.add(completedGate(rung));
            }
        }
        boolean all = !chapters.isEmpty();
        boolean anyCountable = false;
        for (Chapter chapter : chapters.values()) {
            if (!countable(chapter, quests)) {
                continue;
            }
            anyCountable = true;
            if (CompletionSignals.chapterComplete(chapter, quests, playerCompleted, serverCompleted)) {
                gates.add(chapterGate(chapter.id()));
            } else {
                all = false;
            }
        }
        if (all && anyCountable) {
            gates.add(ALL_CHAPTERS);
        }
        return gates;
    }

    /** A chapter with no loaded quest (a sibling pack whose mod is absent) does not count either way. */
    private static boolean countable(Chapter chapter, Map<Identifier, Quest> quests) {
        for (Chapter.Entry entry : chapter.quests()) {
            if (quests.containsKey(entry.quest())) {
                return true;
            }
        }
        return false;
    }

    /** Open every reached milestone gate for a player, online or offline. Safe to call repeatedly. */
    public static void record(MinecraftServer server, UUID player, List<Identifier> gates) {
        for (Identifier g : gates) {
            ProgressionGates.open(server, player, g);
        }
    }

    /** Record the first-contract milestone. Safe to call repeatedly. */
    public static void recordFirstContract(MinecraftServer server, UUID player) {
        ProgressionGates.open(server, player, FIRST_CONTRACT);
    }
}
