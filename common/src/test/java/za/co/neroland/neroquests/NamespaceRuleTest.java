package za.co.neroland.neroquests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.reward.GateReward;
import za.co.neroland.neroquests.quest.reward.ReputationReward;

/** AUD-23: gate and reputation rewards are only valid in quests of the neroquests namespace. */
class NamespaceRuleTest {

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static Quest quest(String namespace, String reward) {
        String json = "{\"title\":\"t\",\"objectives\":[{\"type\":\"neroquests:quest_complete\","
                + "\"quest\":\"neroquests:a\"}],\"rewards\":[{\"type\":\"neroquests:xp\",\"amount\":5},"
                + reward + "]}";
        Quest.Data data = Quest.DATA_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        return new Quest(Identifier.fromNamespaceAndPath(namespace, "q"), data);
    }

    private static final String GATE = "{\"type\":\"neroquests:gate\",\"gate\":\"nerolandcore:deep_space\"}";
    private static final String REPUTATION =
            "{\"type\":\"neroquests:reputation\",\"faction\":\"nerofactions:miners_union\",\"amount\":5}";
    private static final String ITEM = "{\"type\":\"neroquests:item\",\"item\":\"minecraft:stone\"}";

    @Test
    void neroquestsQuestsMayUseThem() {
        assertNull(QuestDefinitions.restrictedRewardOutsideNamespace(quest("neroquests", GATE)));
        assertNull(QuestDefinitions.restrictedRewardOutsideNamespace(quest("neroquests", REPUTATION)));
    }

    @Test
    void otherNamespacesAreRefused() {
        assertEquals(GateReward.TYPE_ID, QuestDefinitions.restrictedRewardOutsideNamespace(quest("mypack", GATE)));
        assertEquals(ReputationReward.TYPE_ID,
                QuestDefinitions.restrictedRewardOutsideNamespace(quest("mypack", REPUTATION)));
        assertNull(QuestDefinitions.restrictedRewardOutsideNamespace(quest("mypack", ITEM)));
    }
}
