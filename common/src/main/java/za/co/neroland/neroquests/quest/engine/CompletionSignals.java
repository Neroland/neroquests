package za.co.neroland.neroquests.quest.engine;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerolandcore.event.ThresholdEvents;
import za.co.neroland.nerolandcore.event.ThresholdEvents.ThresholdCrossing;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestStates;

/**
 * Anonymous completion signals for sibling mods (C3), published on Neroland Core's
 * {@code ThresholdEvents} bus under the source {@code neroquests}:
 *
 * <ul>
 *   <li>{@value #QUEST_COMPLETED_PATH} — on every quest completion: {@code scope} = the quest id,
 *       {@code value} = {@code threshold} = how many times that quest has now been completed in this
 *       world, {@code rising} = true;</li>
 *   <li>{@value #CHAPTER_COMPLETED_PATH} — when a completion finishes every quest of its chapter for
 *       that player: {@code scope} = the chapter id, {@code value} = the world's chapter completion
 *       count.</li>
 * </ul>
 *
 * <p>A crossing names a place or system, never a person (Core's contract): the scope is a pack id and
 * the value a world-wide count, so nothing here identifies who completed it. A sibling that needs the
 * player uses a {@code neroquests:mod_hook} reward instead (a record gate opened for that player).
 *
 * <p>Server thread only; registered after reward execution, so a sibling hears about a completion only
 * once its payout was attempted.
 */
public final class CompletionSignals {

    /** Path of the per-quest channel. */
    public static final String QUEST_COMPLETED_PATH = "quest_completed";

    /** Path of the per-chapter channel. */
    public static final String CHAPTER_COMPLETED_PATH = "chapter_completed";

    /** Channel {@code neroquests:quest_completed}. */
    public static final Identifier QUEST_COMPLETED =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, QUEST_COMPLETED_PATH);

    /** Channel {@code neroquests:chapter_completed}. */
    public static final Identifier CHAPTER_COMPLETED =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, CHAPTER_COMPLETED_PATH);

    private CompletionSignals() {
    }

    /** Subscribe to the completion channel. Called once from common init, after reward execution. */
    public static void init() {
        QuestCompletionListeners.onCompletion(CompletionSignals::onCompletion);
    }

    private static void onCompletion(QuestCompletion completion) {
        QuestProgressState state = QuestProgressState.get(completion.server());
        Quest quest = completion.quest();
        long count = state.recordQuestCompletion(quest.id());
        ThresholdEvents.fire(NeroQuestsCommon.MOD_ID, questCrossing(quest.id(), count));

        Optional<Identifier> chapterId = QuestDefinitions.chapterOf(quest.id());
        if (chapterId.isEmpty()) {
            return;
        }
        Chapter chapter = QuestDefinitions.chaptersForServer(completion.server()).get(chapterId.get());
        if (chapter != null && chapterComplete(chapter, QuestDefinitions.questsForServer(completion.server()),
                state.completedQuests(completion.player()), state.completedServerQuests())) {
            long chapterCount = state.recordChapterCompletion(chapter.id());
            ThresholdEvents.fire(NeroQuestsCommon.MOD_ID, chapterCrossing(chapter.id(), chapterCount));
        }
    }

    /** The crossing for one quest completion: scope = quest id, value = threshold = world count. */
    public static ThresholdCrossing questCrossing(Identifier quest, long worldCount) {
        return new ThresholdCrossing(QUEST_COMPLETED, quest.toString(), worldCount, worldCount, true);
    }

    /** The crossing for one chapter completion: scope = chapter id, value = threshold = world count. */
    public static ThresholdCrossing chapterCrossing(Identifier chapter, long worldCount) {
        return new ThresholdCrossing(CHAPTER_COMPLETED, chapter.toString(), worldCount, worldCount, true);
    }

    /**
     * Whether every loaded quest {@code chapter} lists is complete for this holder, each resolved in its
     * own scope (a server quest by the world's completions). A chapter with no loaded quest is never
     * "complete".
     */
    public static boolean chapterComplete(Chapter chapter, Map<Identifier, Quest> definitions,
                                          Set<Identifier> playerCompleted, Set<Identifier> serverCompleted) {
        boolean any = false;
        for (Chapter.Entry entry : chapter.quests()) {
            if (!definitions.containsKey(entry.quest())) {
                continue;
            }
            any = true;
            if (!QuestStates.prerequisiteMet(entry.quest(), definitions, playerCompleted, serverCompleted)) {
                return false;
            }
        }
        return any;
    }
}
