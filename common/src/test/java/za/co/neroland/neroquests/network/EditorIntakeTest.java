package za.co.neroland.neroquests.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.objective.ManualObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;

/** J2: RT-02 (limits before decoding), RT-09 (coalesced resync), RT-12 (no hidden ids in custom quests). */
class EditorIntakeTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final long T0 = 1_760_000_000_000L;

    @Test
    void inboundCapDropsTheFloodBeforeAnythingIsQueued() {
        EditorIntake intake = new EditorIntake();
        int queued = 0;
        for (int i = 0; i < 1000; i++) {
            if (intake.inbound(PLAYER, T0 + i)) { // 1000 packets within one second
                queued++;
            }
        }
        assertTrue(queued <= EditorIntake.INBOUND_PER_SECOND + 40, "about 40/s plus refill: " + queued);
        assertTrue(queued >= EditorIntake.INBOUND_PER_SECOND);
        assertFalse(intake.inbound(null, T0), "no key, no queue");
    }

    @Test
    void refusalsAreAnsweredAtMostTwiceASecond() {
        EditorIntake intake = new EditorIntake();
        int replies = 0;
        for (int i = 0; i < 100; i++) {
            if (intake.replyToRefusal(PLAYER, T0)) {
                replies++;
            }
        }
        assertEquals(EditorIntake.REPLIES_PER_SECOND, replies);
        assertTrue(intake.replyToRefusal(PLAYER, T0 + 1_000L), "a second later");
    }

    @Test
    void globalSlotsBoundAdmittedWork() {
        EditorIntake intake = new EditorIntake();
        int admitted = 0;
        for (int i = 0; i < 1000; i++) {
            if (intake.globalSlot(T0)) {
                admitted++;
            }
        }
        assertEquals(EditorIntake.GLOBAL_PER_SECOND, admitted);
    }

    @Test
    void customResyncIsCoalescedToOncePerSecond() {
        assertTrue(QuestSync.customResyncDue(T0 - 5_000L, T0));
        assertFalse(QuestSync.customResyncDue(T0, T0 + 999L));
        assertTrue(QuestSync.customResyncDue(T0, T0 + QuestSync.CUSTOM_RESYNC_MILLIS));
        assertTrue(QuestSync.customResyncDue(T0, T0 - 1L), "a clock step back never stalls the resync");
    }

    private static Quest quest(String id, List<String> prerequisites, List<ObjectiveSpec> objectives,
                               Optional<Identifier> gate) {
        return new Quest(Identifier.parse(id), "t", "", Quest.DEFAULT_ICON,
                prerequisites.stream().map(Identifier::parse).toList(), objectives, List.of(), QuestScope.PLAYER, gate);
    }

    @Test
    void customQuestsNamingAHiddenQuestAreNotSentToThatViewer() {
        Map<Identifier, Quest> definitions = new LinkedHashMap<>();
        Quest hidden = quest("mypack:secret/vault", List.of(), List.of(new ManualObjective("x")),
                Optional.of(Identifier.parse("mypack:vault_gate")));
        Quest viaPrerequisite = quest("custom:chap2345/aaaa2222", List.of("mypack:secret/vault"),
                List.of(new ManualObjective("x")), Optional.empty());
        Quest viaObjective = quest("custom:chap2345/bbbb3333", List.of(),
                List.of(new QuestCompleteObjective(Identifier.parse("mypack:secret/vault"))), Optional.empty());
        Quest chained = quest("custom:chap2345/cccc4444", List.of("custom:chap2345/aaaa2222"),
                List.of(new ManualObjective("x")), Optional.empty());
        Quest unrelated = quest("custom:chap2345/dddd5555", List.of(), List.of(new ManualObjective("x")),
                Optional.empty());
        for (Quest q : List.of(hidden, viaPrerequisite, viaObjective, chained, unrelated)) {
            definitions.put(q.id(), q);
        }
        Set<Identifier> closed = new LinkedHashSet<>(definitions.keySet());
        closed.remove(hidden.id()); // the viewer's gate is closed
        QuestSync.hideUnseenReferences(definitions, closed);
        assertEquals(Set.of(unrelated.id()), closed, "every custom quest leading to the hidden id is withheld");

        Set<Identifier> open = new LinkedHashSet<>(definitions.keySet());
        QuestSync.hideUnseenReferences(definitions, open);
        assertEquals(definitions.keySet(), open, "a viewer who sees the gated quest gets everything");
    }
}
