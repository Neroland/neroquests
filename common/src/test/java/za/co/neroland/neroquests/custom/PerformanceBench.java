package za.co.neroland.neroquests.custom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.network.EditorIntake;
import za.co.neroland.neroquests.network.EditorPayloads;
import za.co.neroland.neroquests.network.QuestDefinitionsPayload;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestStates;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.ItemTarget;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.XpReward;

/**
 * K3 (§16.5): microbenchmark numbers for 100 players × (28 spine + 60 pack + 200 custom) quests.
 *
 * <p>NOT part of CI: skipped unless the test JVM has {@code -Dneroquests.bench=true}, which
 * {@code fabric/build.gradle} forwards only when Gradle is run with {@code -PneroquestsBench}:
 * {@code gradlew :fabric:26.2:test --tests *PerformanceBench -PneroquestsBench}. Results are written to
 * {@code build/neroquests-bench.txt} under the test working directory and to stdout.
 *
 * <p>Pure-JVM only. It measures the quest-state rule ({@link QuestStates}), definitions-sync encoding and
 * paging ({@link QuestDefinitionsPayload}), the custom store's serialised size and the editor packet path
 * up to the rate limiter ({@link EditGate}). It does NOT measure the real 1 Hz sweep (inventory scans,
 * registry and structure lookups, Core gate reads, saves) — that needs a running server and is profiled in
 * the developer's smoke test.
 */
@Tag("benchmark")
@EnabledIfSystemProperty(named = "neroquests.bench", matches = "true")
class PerformanceBench {

    private static final int PLAYERS = 100;
    private static final int SPINE = 28;
    private static final int PACK = 60;
    private static final int CUSTOM = 200;
    private static final Identifier STONE = Identifier.parse("minecraft:stone");
    private static final Identifier GATE = Identifier.parse("nerolandcore:reached_orbit");

    private final StringWriter report = new StringWriter();
    private final PrintWriter out = new PrintWriter(report);

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    // --- fixtures -----------------------------------------------------------

    private static ObjectiveSpec collect(String item, int count) {
        return new CollectItemObjective(new ItemTarget(Optional.of(Identifier.parse(item)), Optional.empty()),
                count, false);
    }

    private static final SplittableRandom TEXT_RANDOM = new SplittableRandom(1234);

    /**
     * Random text of exactly {@code codePoints}: lower-case letters and spaces for {@code 'x'}, CJK
     * ideographs (3 bytes each in UTF-8) otherwise — random so the on-disk gzip size is not flattered.
     */
    private static String text(char fill, int codePoints) {
        StringBuilder out = new StringBuilder(codePoints);
        for (int i = 0; i < codePoints; i++) {
            if (fill == 'x') {
                int roll = TEXT_RANDOM.nextInt(27);
                out.append(roll == 26 && i > 0 && i < codePoints - 1 ? ' ' : (char) ('a' + roll % 26));
            } else {
                out.append((char) (0x4E00 + TEXT_RANDOM.nextInt(0x5000)));
            }
        }
        return out.toString();
    }

    private static List<ObjectiveSpec> maxObjectives() {
        List<ObjectiveSpec> list = new ArrayList<>();
        for (int i = 0; i < CustomLimits.MAX_OBJECTIVES; i++) {
            list.add(collect("minecraft:deepslate_emerald_ore", CustomLimits.MAX_COUNT));
        }
        return list;
    }

    private static List<RewardSpec> maxRewards() {
        List<RewardSpec> list = new ArrayList<>();
        list.add(new XpReward(1000));
        for (int i = 1; i < CustomLimits.MAX_REWARDS; i++) {
            list.add(new ItemReward(Identifier.parse("minecraft:enchanted_golden_apple"), 64));
        }
        return list;
    }

    /** Spine and pack: two objectives, xp + item, prerequisite chains of length 5, every third gated. */
    private static Map<Identifier, Quest> definitions(char textFill) {
        Map<Identifier, Quest> defs = new LinkedHashMap<>();
        List<ObjectiveSpec> two = List.of(collect("minecraft:iron_ingot", 16), collect("minecraft:coal", 32));
        List<RewardSpec> rewards = List.of(new XpReward(50), new ItemReward(Identifier.parse("minecraft:bread"), 8));
        for (int i = 0; i < SPINE; i++) {
            Identifier id = Identifier.fromNamespaceAndPath("neroquests", "chapter" + (i / 7) + "/quest_" + i);
            List<Identifier> pre = i % 7 == 0 ? List.of()
                    : List.of(Identifier.fromNamespaceAndPath("neroquests", "chapter" + (i / 7) + "/quest_" + (i - 1)));
            QuestScope scope = i % 9 == 8 ? QuestScope.SERVER : QuestScope.PLAYER;
            defs.put(id, new Quest(id, "quest.neroquests.quest_" + i + ".title", "quest.neroquests.quest_" + i + ".desc",
                    STONE, pre, two, rewards, scope, i % 3 == 2 ? Optional.of(GATE) : Optional.empty()));
        }
        String[] packs = {"nerospace", "nerotech", "neropower", "nerologistics", "nerocolonies"};
        for (int i = 0; i < PACK; i++) {
            String ns = packs[i % packs.length];
            Identifier id = Identifier.fromNamespaceAndPath(ns, "log/quest_" + i);
            List<Identifier> pre = i < packs.length ? List.of()
                    : List.of(Identifier.fromNamespaceAndPath(ns, "log/quest_" + (i - packs.length)));
            defs.put(id, new Quest(id, "quest." + ns + ".quest_" + i + ".title", "quest." + ns + ".quest_" + i + ".desc",
                    STONE, pre, two, rewards, QuestScope.PLAYER, i % 3 == 0 ? Optional.of(GATE) : Optional.empty()));
        }
        List<ObjectiveSpec> objectives = maxObjectives();
        List<RewardSpec> customRewards = maxRewards();
        for (int i = 0; i < CUSTOM; i++) {
            Identifier id = customId(i);
            List<Identifier> pre = i % 10 == 0 ? List.of() : List.of(customId(i - 1));
            defs.put(id, new Quest(id, text(textFill, TextSanitizer.TITLE_MAX), text(textFill, TextSanitizer.DESCRIPTION_MAX),
                    STONE, pre, objectives, customRewards, QuestScope.PLAYER, Optional.empty()));
        }
        return defs;
    }

    private static Identifier customId(int i) {
        return Identifier.fromNamespaceAndPath("custom", String.format(Locale.ROOT, "chap%04d/q%07d", i / 10, i));
    }

    private static final class Holder {
        final Map<Identifier, QuestProgress> progress = new LinkedHashMap<>();
        final Set<Identifier> completed = new HashSet<>();
        final Set<Identifier> gates = new HashSet<>();
    }

    private static List<Holder> players(Map<Identifier, Quest> defs, SplittableRandom random) {
        List<Holder> holders = new ArrayList<>();
        for (int p = 0; p < PLAYERS; p++) {
            Holder holder = new Holder();
            if (random.nextBoolean()) {
                holder.gates.add(GATE);
            }
            for (Quest quest : defs.values()) {
                if (quest.scope() != QuestScope.PLAYER) {
                    continue;
                }
                int roll = random.nextInt(4);
                if (roll == 0) {
                    holder.progress.put(quest.id(), new QuestProgress(List.of(Integer.valueOf(16), Integer.valueOf(32)), 1L));
                    holder.completed.add(quest.id());
                } else if (roll == 1) {
                    holder.progress.put(quest.id(), new QuestProgress(List.of(Integer.valueOf(3)), 0L));
                }
            }
            holders.add(holder);
        }
        return holders;
    }

    // --- benchmark ----------------------------------------------------------

    @Test
    void run() throws IOException {
        out.printf(Locale.ROOT, "NeroQuests K3 microbenchmark — %d players x (%d spine + %d pack + %d custom) = %d quests%n",
                PLAYERS, SPINE, PACK, CUSTOM, SPINE + PACK + CUSTOM);
        out.printf(Locale.ROOT, "JVM %s, %d cores%n%n", System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors());
        Map<Identifier, Quest> defs = definitions('x');
        questStates(defs);
        syncPayload(defs, "ASCII text", 'x');
        syncPayload(definitions('文'), "3-byte UTF-8 text (worst case)", '文');
        customStore('x', "ASCII text");
        customStore('文', "3-byte UTF-8 text (worst case)");
        editorFlood();
        out.println();
        out.println("NOT measured: the real 1 Hz sweep (inventory scans, registry/structure lookups, Core gate");
        out.println("reads, saves). Profile it in the smoke test with many players online.");
        out.flush();
        String text = report.toString();
        System.out.println(text);
        Path file = Path.of("build", "neroquests-bench.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private void questStates(Map<Identifier, Quest> defs) {
        List<Holder> holders = players(defs, new SplittableRandom(42));
        Set<Identifier> serverCompleted = new HashSet<>();
        int sink = 0;
        // Warm-up, then measured full sweeps (every quest for every player, as the engine/sync do).
        for (int round = 0; round < 300; round++) {
            sink += sweep(defs, holders, serverCompleted);
        }
        int rounds = 2000;
        long start = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            sink += sweep(defs, holders, serverCompleted);
        }
        long elapsed = System.nanoTime() - start;
        double perSweepMs = elapsed / 1e6 / rounds;
        double perPair = (double) elapsed / rounds / ((long) PLAYERS * defs.size());
        out.println("(a) Quest-state evaluation (QuestStates.stateOf for every quest, every player)");
        out.printf(Locale.ROOT, "    %.3f ms per full pass of %d players x %d quests (%.0f ns per player-quest)%n",
                perSweepMs, PLAYERS, defs.size(), perPair);
        out.printf(Locale.ROOT, "    = %.2f%% of one second if run once per second (1 Hz)%n", perSweepMs / 10.0);
        out.printf(Locale.ROOT, "    (checksum %d)%n%n", Integer.valueOf(sink & 0xff));
    }

    private static int sweep(Map<Identifier, Quest> defs, List<Holder> holders, Set<Identifier> serverCompleted) {
        int available = 0;
        for (Holder holder : holders) {
            for (Quest quest : defs.values()) {
                QuestProgress progress = holder.progress.getOrDefault(quest.id(), QuestProgress.EMPTY);
                QuestStates.State state = QuestStates.stateOf(quest, defs, holder.completed, serverCompleted,
                        progress, holder.gates);
                if (state == QuestStates.State.AVAILABLE || state == QuestStates.State.IN_PROGRESS) {
                    available++;
                }
            }
        }
        return available;
    }

    private void syncPayload(Map<Identifier, Quest> defs, String label, char fill) {
        List<QuestDefinitionsPayload.Entry> entries = new ArrayList<>();
        long start = System.nanoTime();
        int spineBytes = 0;
        int packBytes = 0;
        int customBytes = 0;
        for (Quest quest : defs.values()) {
            JsonObject extra = null;
            if (quest.id().getNamespace().equals("custom")) {
                extra = new JsonObject();
                extra.addProperty("repeatable", true);
                extra.addProperty("cooldown_seconds", 86_400);
            }
            QuestDefinitionsPayload.Entry entry = QuestDefinitionsPayload.quest(quest, extra).orElseThrow();
            entries.add(entry);
            switch (quest.source()) {
                case SPINE -> spineBytes += entry.encodedSize();
                case PACK -> packBytes += entry.encodedSize();
                default -> customBytes += entry.encodedSize();
            }
        }
        // Chapters: 4 spine, 5 pack, 20 custom, each listing its quests.
        Map<Identifier, List<Chapter.Entry>> chapters = new LinkedHashMap<>();
        for (Quest quest : defs.values()) {
            String path = quest.id().getPath();
            Identifier chapter = Identifier.fromNamespaceAndPath(quest.id().getNamespace(), path.substring(0, path.indexOf('/')));
            chapters.computeIfAbsent(chapter, c -> new ArrayList<>()).add(new Chapter.Entry(quest.id(), 0, 0));
        }
        int chapterBytes = 0;
        for (Map.Entry<Identifier, List<Chapter.Entry>> chapter : chapters.entrySet()) {
            String title = chapter.getKey().getNamespace().equals("custom") ? text(fill, TextSanitizer.TITLE_MAX)
                    : "chapter." + chapter.getKey().getNamespace() + ".title";
            Chapter value = new Chapter(chapter.getKey(), title, STONE, chapter.getValue());
            QuestDefinitionsPayload.Entry entry = QuestDefinitionsPayload.chapter(value, chapter.getValue()).orElseThrow();
            entries.add(entry);
            chapterBytes += entry.encodedSize();
        }
        long encodeNanos = System.nanoTime() - start;
        String editor = "{\"can_author\":true,\"limits\":{},\"channels\":[],\"authored\":[]}";
        List<QuestDefinitionsPayload> pages = QuestDefinitionsPayload.paginate(1, editor, entries, e -> {
            throw new AssertionError("oversized: " + e.id());
        });
        int total = spineBytes + packBytes + customBytes + chapterBytes;
        out.println("(b) Definitions sync on join (" + label + "; custom quests at max text, 12 objectives, 9 rewards)");
        out.printf(Locale.ROOT, "    entries %d (quests %d + chapters %d), encoded %,d bytes = %.1f KiB%n",
                entries.size(), defs.size(), chapters.size(), total, total / 1024.0);
        out.printf(Locale.ROOT, "    spine %,d B (%d B/quest), pack %,d B (%d B/quest), custom %,d B (%d B/quest), chapters %,d B%n",
                spineBytes, spineBytes / SPINE, packBytes, packBytes / PACK, customBytes, customBytes / CUSTOM, chapterBytes);
        StringBuilder perPage = new StringBuilder();
        for (QuestDefinitionsPayload page : pages) {
            perPage.append(perPage.length() == 0 ? "" : ", ").append(page.entries().size());
        }
        out.printf(Locale.ROOT, "    pages %d (512 KiB budget each), entries per page [%s]%n", pages.size(), perPage);
        out.printf(Locale.ROOT, "    encode (one-off per definitions generation, cached server-side) %.1f ms%n%n",
                encodeNanos / 1e6);
    }

    private void customStore(char fill, String label) throws IOException {
        CustomQuestState state = new CustomQuestState();
        for (int c = 0; c < CUSTOM / 10; c++) {
            state.putChapter(new CustomChapter(String.format(Locale.ROOT, "chap%04d", c), text(fill, TextSanitizer.TITLE_MAX),
                    STONE, c, 1L));
        }
        List<ObjectiveSpec> objectives = maxObjectives();
        List<RewardSpec> rewards = maxRewards();
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < CUSTOM; i++) {
            Identifier id = customId(i);
            String chapter = String.format(Locale.ROOT, "chap%04d", i / 10);
            CustomQuest quest = CustomQuest.create(id, chapter, text(fill, TextSanitizer.TITLE_MAX), STONE, i % 10, 0,
                    objectives, Optional.of(new UUID(random.nextLong(), random.nextLong())), 1_760_000_000_000L)
                    .withText(text(fill, TextSanitizer.TITLE_MAX), text(fill, TextSanitizer.DESCRIPTION_MAX), STONE,
                            1_760_000_000_001L)
                    .withPrerequisites(i % 10 == 0 ? List.of() : List.of(customId(i - 1)), 1_760_000_000_002L)
                    .withRewards(rewards, List.of(), 1_760_000_000_003L);
            state.putQuest(quest);
        }
        JsonElement json = CustomQuestState.persistenceCodec().encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        int jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8).length;
        net.minecraft.nbt.Tag nbt = CustomQuestState.persistenceCodec().encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        CompoundTag root = new CompoundTag();
        root.put("data", nbt);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, compressed);
        long start = System.nanoTime();
        int rounds = 50;
        for (int i = 0; i < rounds; i++) {
            CustomQuestState.persistenceCodec().encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        }
        double encodeMs = (System.nanoTime() - start) / 1e6 / rounds;
        out.println("(c) Custom store, " + CUSTOM + " quests in " + (CUSTOM / 10) + " chapters at max size (" + label + ")");
        out.printf(Locale.ROOT, "    JSON %,d bytes (%.1f KiB, %d B/quest); NBT gzip on disk %,d bytes (%.1f KiB)%n",
                jsonBytes, jsonBytes / 1024.0, jsonBytes / CUSTOM, compressed.size(), compressed.size() / 1024.0);
        out.printf(Locale.ROOT, "    codec encode to NBT %.2f ms per save%n%n", encodeMs);
    }

    private void editorFlood() {
        List<UUID> players = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(9);
        for (int i = 0; i < PLAYERS; i++) {
            players.add(new UUID(random.nextLong(), random.nextLong()));
        }
        Identifier quest = customId(5);
        String objectivesJson = ObjectiveSpec.CODEC.listOf().encodeStart(JsonOps.INSTANCE, maxObjectives())
                .getOrThrow().toString();
        FriendlyByteBuf template = new FriendlyByteBuf(Unpooled.buffer());
        EditorPayloads.SetObjectives.STREAM_CODEC.encode(template, new EditorPayloads.SetObjectives(1, quest, objectivesJson));
        byte[] wire = new byte[template.readableBytes()];
        template.readBytes(wire);

        // Each player sends 1000 packets a second for 120 simulated seconds (12,000,000 requests in all).
        int perSecond = 1000;
        int seconds = 120;
        for (int warm = 0; warm < 2; warm++) {
            flood(players, quest, wire, perSecond, 2, new RateLimiter[] {EditGate.editorBurst(), EditGate.editorMinute()},
                    new long[4], LIMITER_FIRST);
        }
        long[] counts = new long[4];
        RateLimiter[] limiters = {EditGate.editorBurst(), EditGate.editorMinute()};
        long start = System.nanoTime();
        flood(players, quest, wire, perSecond, seconds, limiters, counts, LIMITER_FIRST);
        long elapsed = System.nanoTime() - start;
        // Before J2, EditorRequests decoded the JSON on the server thread before CustomQuests asked EditGate,
        // so a refused request still paid the decode: measure that order too (20 simulated s).
        int shippedSeconds = 20;
        flood(players, quest, wire, perSecond, 1, new RateLimiter[] {EditGate.editorBurst(), EditGate.editorMinute()},
                new long[4], DECODE_FIRST);
        long shippedStart = System.nanoTime();
        flood(players, quest, wire, perSecond, shippedSeconds,
                new RateLimiter[] {EditGate.editorBurst(), EditGate.editorMinute()}, new long[4], DECODE_FIRST);
        long shippedElapsed = System.nanoTime() - shippedStart;
        long shippedTotal = (long) PLAYERS * perSecond * shippedSeconds;
        // J2 (RT-02) as shipped now: inbound cap (40/s per player, excess dropped unanswered), then permission +
        // limiter, then the global slot, and only then the decode; refusals answered at most twice a second.
        long[] j2Counts = new long[6];
        flood(players, quest, wire, perSecond, 1, new RateLimiter[] {EditGate.editorBurst(), EditGate.editorMinute()},
                new long[6], SHIPPED);
        long j2Start = System.nanoTime();
        flood(players, quest, wire, perSecond, seconds,
                new RateLimiter[] {EditGate.editorBurst(), EditGate.editorMinute()}, j2Counts, SHIPPED);
        long j2Elapsed = System.nanoTime() - j2Start;
        long total = (long) PLAYERS * perSecond * seconds;
        out.println("(d) Editor packets under the rate limit (decode set_objectives with 12 objectives, EditGate,");
        out.println("    strict objective decode + CustomRules for accepted requests only)");
        out.printf(Locale.ROOT, "    %,d requests from %d players over %d simulated s: accepted %,d, rate-limited %,d%n",
                total, PLAYERS, seconds, counts[0], counts[1]);
        out.printf(Locale.ROOT, "    accepted per player: first minute %d (full 200-token bucket + 200/min refill), "
                + "second minute %d (sustained)%n", (counts[0] - counts[3]) / PLAYERS, counts[3] / PLAYERS);
        out.printf(Locale.ROOT, "    %.1f ms wall in total = %.0f ns per request; %.2f ms of work per simulated second%n",
                elapsed / 1e6, (double) elapsed / total, elapsed / 1e6 / seconds);
        out.printf(Locale.ROOT, "    packet %d bytes on the wire (checksum %d)%n", wire.length, Long.valueOf(counts[2] & 0xff));
        out.printf(Locale.ROOT, "    pre-J2 order (JSON decoded before the limiter, every request): %.0f ns per request; "
                + "%.1f ms of server-thread work per simulated second at 100,000 requests/s%n",
                (double) shippedElapsed / shippedTotal, shippedElapsed / 1e6 / shippedSeconds);
        out.printf(Locale.ROOT, "    J2 shipped order (inbound cap 40/s, then permission + limiter, global slot, decode): "
                + "%.0f ns per request; %.2f ms of work per simulated second at 100,000 requests/s%n",
                (double) j2Elapsed / total, j2Elapsed / 1e6 / seconds);
        out.printf(Locale.ROOT, "    J2: dropped at the inbound cap %,d, refused after it %,d (answered %,d), decoded %,d%n",
                j2Counts[4], j2Counts[1], j2Counts[5], j2Counts[0]);
        out.println("    The mutation itself (QuestDefinitions.preview, save, re-sync) needs a server and is not timed here;");
        out.println("    the limiter caps it at 200 per player per minute.");
    }

    private static final int LIMITER_FIRST = 0;
    private static final int DECODE_FIRST = 1;
    private static final int SHIPPED = 2;

    private static void flood(List<UUID> players, Identifier subject, byte[] wire, int perSecond, int seconds,
                              RateLimiter[] limiters, long[] counts, int mode) {
        boolean decodeFirst = mode == DECODE_FIRST;
        EditorIntake intake = new EditorIntake();
        long now = 1_760_000_000_000L;
        long step = 1000L / perSecond == 0 ? 1 : 1000L / perSecond;
        for (int s = 0; s < seconds; s++) {
            for (int tick = 0; tick < perSecond; tick++) {
                long at = now + s * 1000L + tick * step;
                for (UUID player : players) {
                    ByteBuf raw = Unpooled.wrappedBuffer(wire);
                    EditorPayloads.SetObjectives packet = EditorPayloads.SetObjectives.STREAM_CODEC
                            .decode(new FriendlyByteBuf(raw));
                    if (mode == SHIPPED && !intake.inbound(player, at)) {
                        counts[4]++;
                        continue;
                    }
                    List<ObjectiveSpec> early = decodeFirst ? ObjectiveSpec.CODEC.listOf()
                            .parse(JsonOps.INSTANCE, JsonParser.parseString(packet.objectivesJson())).getOrThrow() : null;
                    Optional<String> refusal = EditGate.refusal(false, true, player, at, limiters[0], limiters[1],
                            packet.quest());
                    if (mode == SHIPPED && refusal.isEmpty() && !intake.globalSlot(at)) {
                        refusal = Optional.of("rate_limited");
                    }
                    if (mode == SHIPPED && refusal.isPresent() && intake.replyToRefusal(player, at)) {
                        counts[5]++;
                    }
                    if (refusal.isEmpty()) {
                        List<ObjectiveSpec> parsed = early != null ? early : ObjectiveSpec.CODEC.listOf()
                                .parse(JsonOps.INSTANCE, JsonParser.parseString(packet.objectivesJson())).getOrThrow();
                        counts[0]++;
                        if (s >= 60) {
                            counts[3]++;
                        }
                        counts[2] += CustomRules.objectives(parsed).isEmpty() ? parsed.size() : 0;
                    } else {
                        counts[1]++;
                    }
                }
            }
        }
    }
}
