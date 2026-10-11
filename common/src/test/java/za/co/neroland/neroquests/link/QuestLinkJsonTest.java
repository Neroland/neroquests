package za.co.neroland.neroquests.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import za.co.neroland.nerolandcore.link.LinkAlert;
import za.co.neroland.neroquests.data.OwedReward;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.QuestSource;

/** G1: link schema 2 JSON builders, alert dedupe and English text lookup. No Minecraft bootstrap. */
class QuestLinkJsonTest {

    private static final Function<String, String> ENGLISH = Map.of(
            "quest.nerospace.log.first_light.title", "First Light",
            "chapter.nerospace.log.title", "Starfarer's Log")::get;

    private static Identifier id(String raw) {
        return Identifier.parse(raw);
    }

    @Test
    void packQuestKeepsKeysAndAddsEnglish() {
        JsonObject row = new JsonObject();
        QuestLinkJson.questIdentity(row, id("nerospace:log/first_light"), QuestSource.PACK,
                "quest.nerospace.log.first_light.title", "quest.nerospace.log.first_light.desc",
                id("nerospace:log"), false, ENGLISH);
        assertEquals("pack", row.get("source").getAsString());
        assertEquals("nerospace", row.get("namespace").getAsString());
        assertEquals("nerospace:log", row.get("chapter").getAsString());
        assertFalse(row.get("mod_present").getAsBoolean());
        assertEquals("quest.nerospace.log.first_light.title", row.get("title").getAsString());
        assertEquals("First Light", row.get("title_text").getAsString());
        assertEquals("quest.nerospace.log.first_light.desc", row.get("description").getAsString());
        assertFalse(row.has("description_text"), "unresolved key: no _text, the client shows the key");
    }

    @Test
    void customQuestSendsPlainTextOnly() {
        JsonObject row = new JsonObject();
        QuestLinkJson.questIdentity(row, id("custom:abc/def"), QuestSource.CUSTOM, "Build a bridge", "",
                null, true, ENGLISH);
        assertEquals("custom", row.get("source").getAsString());
        assertFalse(row.has("title"), "player text never under the key field");
        assertEquals("Build a bridge", row.get("title_text").getAsString());
        assertFalse(row.has("description") || row.has("description_text"));
        assertFalse(row.has("chapter"));
        assertTrue(row.get("mod_present").getAsBoolean());
    }

    @Test
    void chapterRowCarriesSourceOrderAndPresence() {
        Chapter chapter = new Chapter(id("nerospace:log"), "chapter.nerospace.log.title", Chapter.DEFAULT_ICON, List.of());
        JsonArray quests = new JsonArray();
        quests.add("nerospace:log/first_light");
        JsonObject row = QuestLinkJson.chapterRow(chapter, 3, quests, ENGLISH);
        assertEquals("nerospace:log", row.get("id").getAsString());
        assertEquals("Starfarer's Log", row.get("title_text").getAsString());
        assertEquals("pack", row.get("source").getAsString());
        assertEquals("nerospace", row.get("namespace").getAsString());
        assertTrue(row.get("mod_present").getAsBoolean());
        assertEquals(3, row.get("order").getAsInt());
        assertEquals(1, row.getAsJsonArray("quests").size());
    }

    @Test
    void owedRowsCarryNoIdentity() {
        OwedReward credits = new OwedReward("nq:7:neroquests:groundwork/first_tools", id("neroeconomy:credits"), 10,
                "quest_reward", id("neroquests:groundwork/first_tools"), 1234L, 2, true);
        JsonObject row = QuestLinkJson.owedRow(credits);
        assertEquals("nq:7:neroquests:groundwork/first_tools", row.get("key").getAsString());
        assertEquals("currency", row.get("type").getAsString());
        assertEquals(10L, row.get("amount").getAsLong());
        assertEquals("neroquests:groundwork/first_tools", row.get("quest").getAsString());
        assertEquals(1234L, row.get("created_at").getAsLong());
        assertTrue(row.get("permanent").getAsBoolean());
        assertFalse(row.has("attempts"));

        OwedReward item = OwedReward.item("nq:i:3:x", id("minecraft:bread"), 4, new CompoundTag(), "quest_reward",
                id("neroquests:groundwork/first_tools"), 5L);
        JsonObject itemRow = QuestLinkJson.owedRow(item);
        assertEquals("minecraft:bread", itemRow.get("item").getAsString());
        assertEquals(4L, itemRow.get("count").getAsLong());
        assertFalse(itemRow.has("amount") || itemRow.has("currency"));

        OwedReward contract = new OwedReward("nq:dc:1:neroquests:iron", id("neroeconomy:credits"), 5,
                "daily_contract", id("neroquests:iron"), 1L, 0);
        assertFalse(QuestLinkJson.owedRow(contract).has("quest"), "contracts are not quests");

        JsonObject section = QuestLinkJson.owedSection(List.of(credits, item));
        assertEquals(2, section.get("count").getAsInt());
        assertEquals(QuestLinkModule.SCHEMA_VERSION, section.get("schema_version").getAsInt());
        String text = section.toString();
        assertFalse(text.matches("(?s).*[0-9a-f]{8}-[0-9a-f]{4}-.*"), "no UUID in owed payloads");
    }

    @Test
    void claimedPayloadReportsDeliveredPart() {
        OwedReward item = OwedReward.item("nq:i:3:x", id("minecraft:bread"), 10, new CompoundTag(), "quest_reward",
                id("neroquests:groundwork/first_tools"), 5L);
        JsonObject payload = QuestLinkEvents.claimedPayload(item, 6, 4);
        assertEquals(6L, payload.get("count").getAsLong());
        assertEquals(4L, payload.get("remaining").getAsLong());
        assertEquals("nq:i:3:x", payload.get("key").getAsString());
        assertFalse(payload.has("created_at") || payload.has("permanent"));
    }

    @Test
    void customAuthoringIsABooleanOnly() {
        JsonObject body = QuestLinkJson.customAuthoring(true);
        assertEquals(Set.of("schema_version", "can_author"), body.keySet());
        assertEquals(2, body.get("schema_version").getAsInt());
    }

    @Test
    void objectiveTextIsShortEnglish() {
        assertEquals("Collect 16 × #minecraft:logs",
                QuestLinkJson.objectiveText(id("neroquests:collect_item"), 16, "#minecraft:logs"));
        assertEquals("Craft minecraft:crafting_table",
                QuestLinkJson.objectiveText(id("neroquests:craft_item"), 1, "minecraft:crafting_table"));
        assertEquals("Confirmed by an editor",
                QuestLinkJson.objectiveText(id("neroquests:manual"), 1, "neroquests:manual"));
    }

    @Test
    void alertsDedupeHourlyAndOnlyForNewChapters() {
        long now = 10_000_000L;
        LinkAlert recent = new LinkAlert(QuestLinkAlerts.REWARD_ALERT_ID, "neroquests", LinkAlert.Severity.INFO,
                QuestLinkAlerts.REWARD_TEXT, now - 1000L, true, 0L);
        LinkAlert old = new LinkAlert(QuestLinkAlerts.REWARD_ALERT_ID, "neroquests", LinkAlert.Severity.INFO,
                QuestLinkAlerts.REWARD_TEXT, now - QuestLinkAlerts.DEDUPE_MILLIS, false, 0L);
        assertFalse(QuestLinkAlerts.mayRaise(List.of(recent), QuestLinkAlerts.REWARD_ALERT_ID, now));
        assertTrue(QuestLinkAlerts.mayRaise(List.of(old), QuestLinkAlerts.REWARD_ALERT_ID, now));
        assertTrue(QuestLinkAlerts.mayRaise(List.of(recent), QuestLinkAlerts.CHAPTER_ALERT_ID, now));

        Identifier a = id("nerospace:log");
        Identifier b = id("custom:x");
        assertFalse(QuestLinkAlerts.hasNewChapter(null, Set.of(a)), "first view only records");
        assertFalse(QuestLinkAlerts.hasNewChapter(Set.of(a, b), Set.of(a)));
        assertTrue(QuestLinkAlerts.hasNewChapter(Set.of(a), Set.of(a, b)));
    }

    @Test
    void englishTextResolvesFromBundledLanguageFile() {
        assertEquals("neroquests", EnglishText.namespaceOf("chapter.neroquests.groundwork.title"));
        assertNull(EnglishText.namespaceOf("nodots"));
        assertEquals("Groundwork", EnglishText.resolve("neroquests", "chapter.neroquests.groundwork.title"));
        assertEquals("What This Book Is",
                EnglishText.resolve("neroquests", "quest.neroquests.groundwork.what_this_book_is.title"));
        assertNull(EnglishText.resolve("neroquests", "quest.neroquests.no_such.title"));
        EnglishText.clear();
    }
}
