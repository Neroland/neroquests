package za.co.neroland.neroquests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroquests.contract.DailyContract;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.CustomEventObjective;
import za.co.neroland.neroquests.quest.objective.ItemTarget;
import za.co.neroland.neroquests.quest.objective.UnknownObjective;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;

/** Pure codec behaviour: no Minecraft bootstrap, no files. */
class CodecTest {

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static <T> DataResult<T> decode(Codec<T> codec, String json) {
        return codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static <T> T decodeOk(Codec<T> codec, String json) {
        DataResult<T> result = decode(codec, json);
        assertTrue(result.error().isEmpty(), () -> "unexpected error: " + result.error().get().message());
        return result.result().orElseThrow();
    }

    @Test
    void questScopeIsCaseInsensitiveAndRoundTrips() {
        assertEquals(QuestScope.PLAYER, decodeOk(QuestScope.CODEC, "\"player\""));
        assertEquals(QuestScope.SERVER, decodeOk(QuestScope.CODEC, "\" SERVER \""));
        JsonElement encoded = QuestScope.CODEC.encodeStart(JsonOps.INSTANCE, QuestScope.SERVER).getOrThrow();
        assertEquals(new JsonPrimitive("server"), encoded);
    }

    @Test
    void questScopeRejectsUnknownNamesWithoutThrowing() {
        DataResult<QuestScope> result = decode(QuestScope.CODEC, "\"galaxy\"");
        assertTrue(result.error().isPresent());
        assertTrue(result.result().isEmpty());
        List<String> errors = new ArrayList<>();
        assertTrue(QuestDefinitions.decodeStrict(decode(Quest.DATA_CODEC,
                "{\"title\":\"t\",\"scope\":\"galaxy\",\"objectives\":[{\"type\":\"neroquests:quest_complete\","
                        + "\"quest\":\"neroquests:a\"}]}"), errors::add).isEmpty());
        assertEquals(1, errors.size());
    }

    @Test
    void anyDecodeErrorDropsTheWholeQuest() {
        String json = "{\"title\":\"t\",\"objectives\":["
                + "{\"type\":\"neroquests:collect_item\",\"item\":\"minecraft:stone\",\"count\":4},"
                + "{\"type\":\"neroquests:collect_item\",\"item\":\"minecraft:stone\",\"count\":0}],"
                + "\"rewards\":[{\"type\":\"neroquests:currency\",\"amount\":10},"
                + "{\"type\":\"neroquests:currency\",\"amount\":-1}]}";
        List<String> errors = new ArrayList<>();
        assertTrue(QuestDefinitions.decodeStrict(decode(Quest.DATA_CODEC, json), errors::add).isEmpty());
        assertFalse(errors.isEmpty());
        // The same file without the bad entries decodes.
        String clean = "{\"title\":\"t\",\"objectives\":["
                + "{\"type\":\"neroquests:collect_item\",\"item\":\"minecraft:stone\",\"count\":4}],"
                + "\"rewards\":[{\"type\":\"neroquests:currency\",\"amount\":10}]}";
        assertTrue(QuestDefinitions.decodeStrict(decode(Quest.DATA_CODEC, clean), e -> { }).isPresent());
    }

    @Test
    void anyDecodeErrorDropsTheWholeChapterOrContract() {
        List<String> errors = new ArrayList<>();
        assertTrue(QuestDefinitions.decodeStrict(decode(Chapter.DATA_CODEC,
                "{\"title\":\"c\",\"quests\":[{\"quest\":\"neroquests:a\",\"x\":0,\"y\":0},{\"x\":1,\"y\":1}]}"),
                errors::add).isEmpty());
        assertTrue(QuestDefinitions.decodeStrict(decode(DailyContract.Data.CODEC,
                "{\"item\":\"minecraft:stone\",\"count\":4,\"reward\":5,\"weight\":0}"), errors::add).isEmpty());
        assertEquals(2, errors.size());
        assertTrue(QuestDefinitions.decodeStrict(decode(DailyContract.Data.CODEC,
                "{\"item\":\"minecraft:stone\",\"count\":4,\"reward\":5}"), errors::add).isPresent());
    }

    @Test
    void audienceAndDirectionRejectUnknownNames() {
        assertEquals(CustomEventObjective.Audience.EVERYONE,
                decodeOk(CustomEventObjective.Audience.CODEC, "\"Everyone\""));
        assertTrue(decode(CustomEventObjective.Audience.CODEC, "\"all\"").error().isPresent());
        assertEquals(CustomEventObjective.Direction.FALLING,
                decodeOk(CustomEventObjective.Direction.CODEC, "\"falling\""));
        assertTrue(decode(CustomEventObjective.Direction.CODEC, "\"sideways\"").error().isPresent());
    }

    @Test
    void customEventDefaultsToWorldAudienceAndAnyDirection() {
        ObjectiveSpec spec = decodeOk(ObjectiveSpec.CODEC,
                "{\"type\":\"neroquests:custom_event\",\"channel\":\"examplemod:storm\"}");
        CustomEventObjective event = (CustomEventObjective) spec;
        assertEquals(CustomEventObjective.Audience.WORLD, event.audience());
        assertEquals(CustomEventObjective.Direction.ANY, event.direction());
        assertEquals(1, event.count());
    }

    @Test
    void itemTargetNeedsExactlyOneOfItemOrTag() {
        Codec<ItemTarget> codec = ItemTarget.MAP_CODEC.codec();
        assertTrue(decode(codec, "{\"item\":\"minecraft:stone\"}").result().isPresent());
        assertTrue(decode(codec, "{\"tag\":\"c:ingots/iron\"}").result().isPresent());
        assertTrue(decode(codec, "{\"item\":\"minecraft:stone\",\"tag\":\"c:ingots/iron\"}").error().isPresent());
        assertTrue(decode(codec, "{}").error().isPresent());
    }

    @Test
    void collectItemConsumeDefaultsToFalse() {
        CollectItemObjective plain = (CollectItemObjective) decodeOk(ObjectiveSpec.CODEC,
                "{\"type\":\"neroquests:collect_item\",\"item\":\"minecraft:stone\",\"count\":4}");
        assertFalse(plain.consume());
        assertEquals(4, plain.count());
        CollectItemObjective handIn = (CollectItemObjective) decodeOk(ObjectiveSpec.CODEC,
                "{\"type\":\"neroquests:collect_item\",\"item\":\"minecraft:stone\",\"consume\":true}");
        assertTrue(handIn.consume());
        assertTrue(decode(ObjectiveSpec.CODEC,
                "{\"type\":\"neroquests:collect_item\",\"item\":\"minecraft:stone\",\"count\":0}").error().isPresent());
    }

    @Test
    void unknownObjectiveTypeDecodesToPlaceholder() {
        ObjectiveSpec spec = decodeOk(ObjectiveSpec.CODEC, "{\"type\":\"examplemod:nonexistent\"}");
        assertTrue(spec instanceof UnknownObjective);
    }

    @Test
    void currencyRewardRejectsNonPositiveAmountsAndDefaultsToCredits() {
        CurrencyReward reward = (CurrencyReward) decodeOk(RewardSpec.CODEC,
                "{\"type\":\"neroquests:currency\",\"amount\":25}");
        assertEquals(CurrencyReward.DEFAULT_CURRENCY, reward.currency());
        assertFalse(reward.oncePerWorld());
        assertTrue(decode(RewardSpec.CODEC, "{\"type\":\"neroquests:currency\",\"amount\":0}").error().isPresent());
        assertTrue(decode(RewardSpec.CODEC, "{\"type\":\"neroquests:currency\",\"amount\":-5}").error().isPresent());
    }
}
