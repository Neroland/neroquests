package za.co.neroland.neroquests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestTypes;

/** AUD-22: per-reward caps in the codecs; anything beyond drops the whole quest (strict decode). */
class RewardCapTest {

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static boolean loads(String reward) {
        String json = "{\"title\":\"t\",\"objectives\":[{\"type\":\"neroquests:quest_complete\","
                + "\"quest\":\"neroquests:a\"}],\"rewards\":[" + reward + "]}";
        List<String> errors = new ArrayList<>();
        boolean ok = QuestDefinitions.decodeStrict(Quest.DATA_CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString(json)), errors::add).isPresent();
        assertTrue(ok == errors.isEmpty());
        return ok;
    }

    @Test
    void xpIsCappedAtOneMillion() {
        assertTrue(loads("{\"type\":\"neroquests:xp\",\"amount\":1000000}"));
        assertFalse(loads("{\"type\":\"neroquests:xp\",\"amount\":1000001}"));
        assertFalse(loads("{\"type\":\"neroquests:xp\",\"amount\":0}"));
    }

    @Test
    void currencyIsCappedAtOneMillionPerReward() {
        assertTrue(loads("{\"type\":\"neroquests:currency\",\"amount\":1000000}"));
        assertFalse(loads("{\"type\":\"neroquests:currency\",\"amount\":1000001}"));
        assertFalse(loads("{\"type\":\"neroquests:currency\",\"currency\":\"mypack:scrip\",\"amount\":9000000000}"));
        assertFalse(loads("{\"type\":\"neroquests:currency\",\"amount\":0}"));
    }

    @Test
    void itemCountIsCappedAtAFullInventory() {
        assertTrue(loads("{\"type\":\"neroquests:item\",\"item\":\"minecraft:stone\",\"count\":2304}"));
        assertFalse(loads("{\"type\":\"neroquests:item\",\"item\":\"minecraft:stone\",\"count\":2305}"));
        assertFalse(loads("{\"type\":\"neroquests:item\",\"item\":\"minecraft:stone\",\"count\":0}"));
    }
}
