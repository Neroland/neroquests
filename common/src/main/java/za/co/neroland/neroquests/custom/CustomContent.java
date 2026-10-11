package za.co.neroland.neroquests.custom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;

/**
 * Turns the custom-quest store into definitions for the shared pipeline (D1): each custom chapter becomes a
 * {@link Chapter} {@code custom:<slug>} whose entries are its quests at their stored node positions, each
 * custom quest a player-scope {@link Quest}. Chapters come out in {@code order}, then slug. Pure.
 *
 * <p>A quest whose stored objective/reward JSON no longer decodes is left out and logged once by id (never
 * its text); its row stays in the store untouched.
 */
public final class CustomContent {

    private static final Set<Identifier> UNDECODABLE_WARNED = new LinkedHashSet<>();

    private CustomContent() {
    }

    /** The definitions of {@code state}. */
    public static QuestDefinitions.Extra build(CustomQuestState state) {
        Map<Identifier, Quest> quests = new LinkedHashMap<>();
        Map<String, List<Chapter.Entry>> entries = new LinkedHashMap<>();
        for (CustomQuest row : state.quests().values()) {
            if (state.chapter(row.chapter()).isEmpty()) {
                continue; // orphaned by a chapter delete that did not finish; never shown
            }
            Optional<Quest> quest = row.toQuest();
            if (quest.isEmpty()) {
                warnUndecodable(row.id());
                continue;
            }
            quests.put(row.id(), quest.get());
            entries.computeIfAbsent(row.chapter(), slug -> new ArrayList<>())
                    .add(new Chapter.Entry(row.id(), row.x(), row.y()));
        }
        List<CustomChapter> ordered = new ArrayList<>(state.chapters().values());
        ordered.sort(Comparator.comparingInt(CustomChapter::order).thenComparing(CustomChapter::slug));
        Map<Identifier, Chapter> chapters = new LinkedHashMap<>();
        for (CustomChapter chapter : ordered) {
            chapters.put(chapter.id(), new Chapter(chapter.id(), chapter.title(), chapter.icon(),
                    List.copyOf(entries.getOrDefault(chapter.slug(), List.of()))));
        }
        return new QuestDefinitions.Extra(quests, chapters);
    }

    private static synchronized void warnUndecodable(Identifier id) {
        if (UNDECODABLE_WARNED.size() < 1024 && UNDECODABLE_WARNED.add(id)) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Custom quest {} has objectives or rewards this installation "
                    + "cannot read (a removed add-on type?); it is not loaded, and kept unchanged.", id);
        }
    }

    /** Forget warn-once state (server stop). */
    static synchronized void reset() {
        UNDECODABLE_WARNED.clear();
    }
}
