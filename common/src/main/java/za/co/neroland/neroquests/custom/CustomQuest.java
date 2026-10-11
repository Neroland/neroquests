package za.co.neroland.neroquests.custom;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * One custom (editor-made) quest as stored (D1). Its id is {@code custom:<chapter slug>/<slug>}, both slugs
 * random. Title, description and {@code manual} labels are sanitised author text.
 *
 * <p>Objectives and rewards are kept as their own JSON (the same codecs datapacks use), decoded when the
 * definitions are built. A row whose JSON no longer decodes (an add-on type was removed) keeps its text,
 * so nothing is lost on the next save; it simply does not load until fixed.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> {@code createdBy} is the author's game UUID — the only identifier the
 * store adds — kept for the data-access export and the "you / another player" label, cleared (the quest
 * survives) by an erasure. It is never logged and never resolved to a name.
 *
 * @param id              {@code custom:<chapter>/<slug>}
 * @param chapter         the owning chapter's slug
 * @param title           sanitised author text, 1..64 code points
 * @param description     sanitised author text, 0..512 code points
 * @param icon            an item id
 * @param x               node column, -512..512
 * @param y               node row, -512..512
 * @param prerequisites   quest ids (any source) that must be complete first
 * @param objectivesJson  the objective list, encoded with {@link ObjectiveSpec#CODEC}
 * @param rewardsJson     the reward list, encoded with {@link RewardSpec#CODEC}
 * @param repeatable      whether a completion re-arms after {@code cooldownSeconds}
 * @param cooldownSeconds repeat cooldown (0 when not repeatable)
 * @param createdBy       the author's UUID, empty when unknown or erased
 * @param createdAt       epoch millis
 * @param updatedAt       epoch millis
 * @param escrow          per reward index, escrowed copies still held (empty when not escrowed)
 */
public record CustomQuest(Identifier id, String chapter, String title, String description, Identifier icon,
                          int x, int y, List<Identifier> prerequisites, String objectivesJson, String rewardsJson,
                          boolean repeatable, int cooldownSeconds, Optional<UUID> createdBy, long createdAt,
                          long updatedAt, List<Integer> escrow) {

    private record Node(int x, int y) {
        static final Codec<Node> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.INT.optionalFieldOf("x", 0).forGetter(Node::x),
                Codec.INT.optionalFieldOf("y", 0).forGetter(Node::y)
        ).apply(inst, Node::of));

        private static Node of(Integer x, Integer y) {
            return new Node(x.intValue(), y.intValue());
        }
    }

    public static final Codec<CustomQuest> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Identifier.CODEC.fieldOf("id").forGetter(CustomQuest::id),
            Codec.STRING.fieldOf("chapter").forGetter(CustomQuest::chapter),
            Codec.STRING.fieldOf("title").forGetter(CustomQuest::title),
            Codec.STRING.optionalFieldOf("description", "").forGetter(CustomQuest::description),
            Identifier.CODEC.optionalFieldOf("icon", Quest.DEFAULT_ICON).forGetter(CustomQuest::icon),
            Node.CODEC.optionalFieldOf("node", new Node(0, 0)).forGetter(q -> new Node(q.x(), q.y())),
            Identifier.CODEC.listOf().optionalFieldOf("prerequisites", List.of()).forGetter(CustomQuest::prerequisites),
            Codec.STRING.optionalFieldOf("objectives", "[]").forGetter(CustomQuest::objectivesJson),
            Codec.STRING.optionalFieldOf("rewards", "[]").forGetter(CustomQuest::rewardsJson),
            Codec.BOOL.optionalFieldOf("repeatable", Boolean.FALSE).forGetter(q -> Boolean.valueOf(q.repeatable())),
            Codec.INT.optionalFieldOf("cooldown_seconds", 0).forGetter(CustomQuest::cooldownSeconds),
            Codec.STRING.optionalFieldOf("created_by").forGetter(q -> q.createdBy().map(UUID::toString)),
            Codec.LONG.optionalFieldOf("created_at", 0L).forGetter(CustomQuest::createdAt),
            Codec.LONG.optionalFieldOf("updated_at", 0L).forGetter(CustomQuest::updatedAt),
            Codec.INT.listOf().optionalFieldOf("escrow", List.of()).forGetter(CustomQuest::escrow)
    ).apply(inst, CustomQuest::decode));

    private static CustomQuest decode(Identifier id, String chapter, String title, String description, Identifier icon,
                                      Node node, List<Identifier> prerequisites, String objectives, String rewards,
                                      Boolean repeatable, Integer cooldown, Optional<String> createdBy, Long createdAt,
                                      Long updatedAt, List<Integer> escrow) {
        return new CustomQuest(id, chapter, title, description, icon, node.x(), node.y(), List.copyOf(prerequisites),
                objectives, rewards, repeatable.booleanValue(), cooldown.intValue(), createdBy.flatMap(CustomQuest::uuid),
                createdAt.longValue(), updatedAt.longValue(), List.copyOf(escrow));
    }

    private static Optional<UUID> uuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The chapter id this quest belongs to ({@code custom:<chapter>}). */
    public Identifier chapterId() {
        return CustomChapter.idOf(chapter);
    }

    /** The decoded objectives, or empty when the stored JSON no longer decodes. */
    public Optional<List<ObjectiveSpec>> objectives() {
        return decodeList(objectivesJson, ObjectiveSpec.CODEC);
    }

    /** The decoded rewards, or empty when the stored JSON no longer decodes. */
    public Optional<List<RewardSpec>> rewards() {
        return decodeList(rewardsJson, RewardSpec.CODEC);
    }

    /** This quest as a definition for the shared pipeline (player scope, no visible gate), if it decodes. */
    public Optional<Quest> toQuest() {
        Optional<List<ObjectiveSpec>> objectives = objectives();
        Optional<List<RewardSpec>> rewards = rewards();
        if (objectives.isEmpty() || rewards.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Quest(id, title, description, icon, prerequisites, objectives.get(), rewards.get(),
                QuestScope.PLAYER, Optional.empty()));
    }

    /** Whether {@code player} wrote this quest (false for an unknown or erased author). */
    public boolean authoredBy(UUID player) {
        return player != null && createdBy.isPresent() && createdBy.get().equals(player);
    }

    /** Remaining escrowed copies of the reward at {@code index} (0 when none or not escrowed). */
    public int escrowAt(int index) {
        return index >= 0 && index < escrow.size() ? Math.max(0, escrow.get(index).intValue()) : 0;
    }

    /** Whether this quest's item rewards are paid from an escrow deposit. */
    public boolean escrowed() {
        return !escrow.isEmpty();
    }

    // --- copies (records are immutable; every mutation makes a new row) ------------

    public CustomQuest withText(String newTitle, String newDescription, Identifier newIcon, long now) {
        return new CustomQuest(id, chapter, newTitle, newDescription, newIcon, x, y, prerequisites, objectivesJson,
                rewardsJson, repeatable, cooldownSeconds, createdBy, createdAt, now, escrow);
    }

    public CustomQuest withNode(int newX, int newY, long now) {
        return new CustomQuest(id, chapter, title, description, icon, newX, newY, prerequisites, objectivesJson,
                rewardsJson, repeatable, cooldownSeconds, createdBy, createdAt, now, escrow);
    }

    public CustomQuest withPrerequisites(List<Identifier> newPrerequisites, long now) {
        return new CustomQuest(id, chapter, title, description, icon, x, y, List.copyOf(newPrerequisites),
                objectivesJson, rewardsJson, repeatable, cooldownSeconds, createdBy, createdAt, now, escrow);
    }

    public CustomQuest withObjectives(List<ObjectiveSpec> objectives, long now) {
        return new CustomQuest(id, chapter, title, description, icon, x, y, prerequisites, encode(objectives,
                ObjectiveSpec.CODEC), rewardsJson, repeatable, cooldownSeconds, createdBy, createdAt, now, escrow);
    }

    public CustomQuest withRewards(List<RewardSpec> rewards, List<Integer> newEscrow, long now) {
        return new CustomQuest(id, chapter, title, description, icon, x, y, prerequisites, objectivesJson,
                encode(rewards, RewardSpec.CODEC), repeatable, cooldownSeconds, createdBy, createdAt, now,
                List.copyOf(newEscrow));
    }

    public CustomQuest withEscrow(List<Integer> newEscrow) {
        return new CustomQuest(id, chapter, title, description, icon, x, y, prerequisites, objectivesJson,
                rewardsJson, repeatable, cooldownSeconds, createdBy, createdAt, updatedAt, List.copyOf(newEscrow));
    }

    public CustomQuest withRepeat(boolean newRepeatable, int newCooldown, long now) {
        return new CustomQuest(id, chapter, title, description, icon, x, y, prerequisites, objectivesJson,
                rewardsJson, newRepeatable, newRepeatable ? newCooldown : 0, createdBy, createdAt, now, escrow);
    }

    /** The same quest with its author forgotten (erasure, D1): the quest itself survives. */
    public CustomQuest withoutAuthor() {
        return new CustomQuest(id, chapter, title, description, icon, x, y, prerequisites, objectivesJson,
                rewardsJson, repeatable, cooldownSeconds, Optional.empty(), createdAt, updatedAt, escrow);
    }

    /** A fresh quest. */
    public static CustomQuest create(Identifier id, String chapter, String title, Identifier icon, int x, int y,
                                     List<ObjectiveSpec> objectives, Optional<UUID> author, long now) {
        return new CustomQuest(id, chapter, title, "", icon, x, y, List.of(), encode(objectives, ObjectiveSpec.CODEC),
                "[]", false, 0, author, now, now, List.of());
    }

    // --- JSON ---------------------------------------------------------------------

    /**
     * Encode a list with its codec as compact JSON. Never puts the list's content in an exception message
     * (it can carry author text, which must never reach a log).
     */
    public static <T> String encode(List<T> values, Codec<T> codec) {
        return codec.listOf().encodeStart(JsonOps.INSTANCE, new ArrayList<>(values)).result()
                .map(Object::toString)
                .orElseThrow(() -> new IllegalStateException("custom quest list could not be encoded"));
    }

    private static <T> Optional<List<T>> decodeList(String json, Codec<T> codec) {
        try {
            return QuestDefinitions.decodeStrict(codec.listOf().parse(JsonOps.INSTANCE, JsonParser.parseString(json)),
                    error -> { }).map(List::copyOf);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
