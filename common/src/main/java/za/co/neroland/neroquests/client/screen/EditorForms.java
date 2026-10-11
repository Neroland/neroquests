package za.co.neroland.neroquests.client.screen;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.CraftItemObjective;
import za.co.neroland.neroquests.quest.objective.CustomEventObjective;
import za.co.neroland.neroquests.quest.objective.EntityTarget;
import za.co.neroland.neroquests.quest.objective.GateOpenObjective;
import za.co.neroland.neroquests.quest.objective.ItemTarget;
import za.co.neroland.neroquests.quest.objective.KillEntityObjective;
import za.co.neroland.neroquests.quest.objective.ManualObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.objective.ReachDimensionObjective;
import za.co.neroland.neroquests.quest.objective.VisitStructureObjective;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.XpReward;

/**
 * The editor's small forms as data (E4/E5), pure: which objective and reward types the editor offers, what
 * each form field means for a type, and the conversion between a filled form ({@link ObjectiveDraft},
 * {@link RewardDraft}) and the codec JSON the server decodes. Also the running reward totals shown against
 * the server's caps.
 *
 * <p>The client never judges content: an id that parses is sent as typed, and the server says whether it
 * exists. The only local check is that an id is <em>shaped</em> like an id, so a typo gets a hint before a
 * round trip.
 */
public final class EditorForms {

    /** What the target field of an objective form holds. */
    public enum Target {
        ITEM(true),
        ENTITY(true),
        DIMENSION(false),
        STRUCTURE(true),
        GATE(false),
        CHANNEL(false),
        QUEST(false),
        LABEL(false);

        private final boolean taggable;

        Target(boolean taggable) {
            this.taggable = taggable;
        }

        /** Whether the field may name a tag ({@code #ns:path}) instead of one id. */
        public boolean taggable() {
            return taggable;
        }
    }

    /** The objective types the editor offers, in menu order — the server's custom-quest allow-list. */
    public enum ObjectiveKind {
        COLLECT_ITEM("collect_item", Target.ITEM, true),
        CRAFT_ITEM("craft_item", Target.ITEM, true),
        KILL_ENTITY("kill_entity", Target.ENTITY, true),
        REACH_DIMENSION("reach_dimension", Target.DIMENSION, false),
        VISIT_STRUCTURE("visit_structure", Target.STRUCTURE, false),
        GATE_OPEN("gate_open", Target.GATE, false),
        CUSTOM_EVENT("custom_event", Target.CHANNEL, true),
        QUEST_COMPLETE("quest_complete", Target.QUEST, false),
        MANUAL("manual", Target.LABEL, false);

        private final String path;
        private final Target target;
        private final boolean counted;

        ObjectiveKind(String path, Target target, boolean counted) {
            this.path = path;
            this.target = target;
            this.counted = counted;
        }

        public String path() {
            return path;
        }

        public Target target() {
            return target;
        }

        /** Whether the type takes a {@code count}. */
        public boolean counted() {
            return counted;
        }

        public Identifier typeId() {
            return Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, path);
        }

        /** The lang key of the type's name in the editor. */
        public String key() {
            return "gui.neroquests.editor.objective_type." + path;
        }

        public ObjectiveKind next() {
            ObjectiveKind[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /** The reward types the editor offers — the server's custom-quest allow-list. */
    public enum RewardKind {
        ITEM("item", true, true),
        XP("xp", false, true),
        CURRENCY("currency", false, true),
        LOOT_TABLE("loot_table", true, false);

        private final String path;
        private final boolean hasId;
        private final boolean hasAmount;

        RewardKind(String path, boolean hasId, boolean hasAmount) {
            this.path = path;
            this.hasId = hasId;
            this.hasAmount = hasAmount;
        }

        public String path() {
            return path;
        }

        public boolean hasId() {
            return hasId;
        }

        public boolean hasAmount() {
            return hasAmount;
        }

        public String key() {
            return "gui.neroquests.editor.reward_type." + path;
        }

        public RewardKind next() {
            RewardKind[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /** One objective form. {@code target} is an id, {@code #tag}, or the label for {@code manual}. */
    public record ObjectiveDraft(ObjectiveKind kind, String target, int count, boolean consume) {

        public static final ObjectiveDraft DEFAULT = new ObjectiveDraft(ObjectiveKind.COLLECT_ITEM, "", 1, false);

        public ObjectiveDraft withKind(ObjectiveKind value) {
            return new ObjectiveDraft(value, value.target() == kind.target() ? target : "", count, consume);
        }

        public ObjectiveDraft withTarget(String value) {
            return new ObjectiveDraft(kind, value, count, consume);
        }

        public ObjectiveDraft withCount(int value) {
            return new ObjectiveDraft(kind, target, value, consume);
        }

        public ObjectiveDraft withConsume(boolean value) {
            return new ObjectiveDraft(kind, target, count, value);
        }
    }

    /** One reward form. */
    public record RewardDraft(RewardKind kind, String id, long amount) {

        public static final RewardDraft DEFAULT = new RewardDraft(RewardKind.XP, "", 10L);

        public RewardDraft withKind(RewardKind value) {
            return new RewardDraft(value, value.hasId() == kind.hasId() ? id : "", value == RewardKind.ITEM ? 1L
                    : value == RewardKind.LOOT_TABLE ? 1L : amount);
        }

        public RewardDraft withId(String value) {
            return new RewardDraft(kind, value, amount);
        }

        public RewardDraft withAmount(long value) {
            return new RewardDraft(kind, id, value);
        }
    }

    /** Reward totals against the editor block's caps. */
    public record Totals(int rewards, int stacks, long xp, long currency) {
    }

    /** Objective counts the server accepts. */
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 1_000_000;

    private EditorForms() {
    }

    // --- parsing helpers ---------------------------------------------------------------------------

    /** Whether the text is a tag reference ({@code #ns:path}). */
    public static boolean isTag(String text) {
        return text != null && text.trim().startsWith("#");
    }

    /** The id in a field (without a leading {@code #}), if it is shaped like a resource id. */
    public static Optional<Identifier> id(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(Identifier.tryParse(trimmed));
    }

    /** A whole number in {@code [min, max]}, or empty. */
    public static Optional<Long> number(String text, long min, long max) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            long value = Long.parseLong(text.trim());
            return value < min || value > max ? Optional.empty() : Optional.of(Long.valueOf(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // --- drafts → codec JSON -----------------------------------------------------------------------

    /**
     * The codec JSON of a filled objective form, or empty when a field is missing or not shaped right (the
     * screen then shows a hint instead of sending).
     */
    public static Optional<JsonObject> objectiveJson(ObjectiveDraft draft) {
        ObjectiveKind kind = draft.kind();
        JsonObject json = new JsonObject();
        json.addProperty("type", kind.typeId().toString());
        if (kind.target() == Target.LABEL) {
            String label = draft.target() == null ? "" : draft.target().trim();
            if (label.isEmpty()) {
                return Optional.empty();
            }
            json.addProperty("label", label);
            return Optional.of(json);
        }
        Optional<Identifier> id = id(draft.target());
        if (id.isEmpty()) {
            return Optional.empty();
        }
        boolean tag = isTag(draft.target());
        if (tag && !kind.target().taggable()) {
            return Optional.empty();
        }
        String field = switch (kind.target()) {
            case ITEM -> tag ? "tag" : "item";
            case ENTITY -> tag ? "tag" : "entity";
            case STRUCTURE -> tag ? "tag" : "structure";
            case DIMENSION -> "dimension";
            case GATE -> "gate";
            case CHANNEL -> "channel";
            case QUEST -> "quest";
            case LABEL -> "label";
        };
        json.addProperty(field, id.get().toString());
        if (kind.counted()) {
            if (draft.count() < MIN_COUNT || draft.count() > MAX_COUNT) {
                return Optional.empty();
            }
            json.addProperty("count", draft.count());
        }
        if (kind == ObjectiveKind.COLLECT_ITEM && draft.consume()) {
            json.addProperty("consume", true);
        }
        return Optional.of(json);
    }

    /** The codec JSON of a filled reward form, or empty when a field is missing or out of range. */
    public static Optional<JsonObject> rewardJson(RewardDraft draft) {
        JsonObject json = new JsonObject();
        json.addProperty("type", Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, draft.kind().path())
                .toString());
        switch (draft.kind()) {
            case ITEM -> {
                Optional<Identifier> id = id(draft.id());
                if (id.isEmpty() || isTag(draft.id()) || draft.amount() < 1 || draft.amount() > 64) {
                    return Optional.empty();
                }
                json.addProperty("item", id.get().toString());
                json.addProperty("count", (int) draft.amount());
            }
            case LOOT_TABLE -> {
                Optional<Identifier> id = id(draft.id());
                if (id.isEmpty() || isTag(draft.id())) {
                    return Optional.empty();
                }
                json.addProperty("table", id.get().toString());
            }
            case XP -> {
                if (draft.amount() < 1 || draft.amount() > 1_000_000) {
                    return Optional.empty();
                }
                json.addProperty("amount", (int) draft.amount());
            }
            case CURRENCY -> {
                if (draft.amount() < 1 || draft.amount() > 1_000_000) {
                    return Optional.empty();
                }
                json.addProperty("amount", draft.amount());
            }
        }
        return Optional.of(json);
    }

    // --- records → drafts / JSON --------------------------------------------------------------------

    /** The form for editing an existing objective, or empty for a type the editor does not offer. */
    public static Optional<ObjectiveDraft> draftOf(ObjectiveSpec spec) {
        if (spec instanceof CollectItemObjective o) {
            return Optional.of(new ObjectiveDraft(ObjectiveKind.COLLECT_ITEM, itemText(o.selector()), o.count(),
                    o.consume()));
        }
        if (spec instanceof CraftItemObjective o) {
            return Optional.of(new ObjectiveDraft(ObjectiveKind.CRAFT_ITEM, itemText(o.selector()), o.count(), false));
        }
        if (spec instanceof KillEntityObjective o) {
            return Optional.of(new ObjectiveDraft(ObjectiveKind.KILL_ENTITY, entityText(o.selector()), o.count(),
                    false));
        }
        if (spec instanceof ReachDimensionObjective o) {
            return Optional.of(new ObjectiveDraft(ObjectiveKind.REACH_DIMENSION, o.dimension().toString(), 1, false));
        }
        if (spec instanceof VisitStructureObjective o) {
            String text = o.structure().map(Identifier::toString)
                    .orElseGet(() -> o.tag().map(tag -> "#" + tag).orElse(""));
            return Optional.of(new ObjectiveDraft(ObjectiveKind.VISIT_STRUCTURE, text, 1, false));
        }
        if (spec instanceof GateOpenObjective o) {
            return Optional.of(new ObjectiveDraft(ObjectiveKind.GATE_OPEN, o.gate().toString(), 1, false));
        }
        if (spec instanceof CustomEventObjective o) {
            return Optional.of(new ObjectiveDraft(ObjectiveKind.CUSTOM_EVENT, o.channel().toString(), o.count(),
                    false));
        }
        if (spec instanceof QuestCompleteObjective o) {
            return Optional.of(new ObjectiveDraft(ObjectiveKind.QUEST_COMPLETE, o.quest().toString(), 1, false));
        }
        if (spec instanceof ManualObjective o) {
            return Optional.of(new ObjectiveDraft(ObjectiveKind.MANUAL, o.label(), 1, false));
        }
        return Optional.empty();
    }

    private static String itemText(ItemTarget target) {
        return target.item().map(Identifier::toString).orElseGet(() -> target.tag().map(tag -> "#" + tag).orElse(""));
    }

    private static String entityText(EntityTarget target) {
        return target.entity().map(Identifier::toString)
                .orElseGet(() -> target.tag().map(tag -> "#" + tag).orElse(""));
    }

    /** A list of objectives as codec JSON (an element that cannot be encoded is dropped). */
    public static JsonArray objectivesJson(List<ObjectiveSpec> specs) {
        JsonArray array = new JsonArray();
        for (ObjectiveSpec spec : specs) {
            ObjectiveSpec.CODEC.encodeStart(JsonOps.INSTANCE, spec).result().ifPresent(array::add);
        }
        return array;
    }

    /** A list of rewards as codec JSON (an element that cannot be encoded is dropped). */
    public static JsonArray rewardsJson(List<RewardSpec> specs) {
        JsonArray array = new JsonArray();
        for (RewardSpec spec : specs) {
            RewardSpec.CODEC.encodeStart(JsonOps.INSTANCE, spec).result().ifPresent(array::add);
        }
        return array;
    }

    /** {@code array} with element {@code index} replaced by {@code element} ({@code index < 0}: appended). */
    public static JsonArray with(JsonArray array, int index, JsonElement element) {
        JsonArray out = array.deepCopy();
        if (index < 0 || index >= out.size()) {
            out.add(element);
        } else {
            out.set(index, element);
        }
        return out;
    }

    /** {@code array} without element {@code index}. */
    public static JsonArray without(JsonArray array, int index) {
        JsonArray out = array.deepCopy();
        if (index >= 0 && index < out.size()) {
            out.remove(index);
        }
        return out;
    }

    /** Running totals of a reward list (item stacks, experience, credits). */
    public static Totals totals(List<RewardSpec> rewards) {
        int stacks = 0;
        long xp = 0L;
        long currency = 0L;
        for (RewardSpec reward : rewards) {
            if (reward instanceof ItemReward) {
                stacks++;
            } else if (reward instanceof XpReward x) {
                xp += x.amount();
            } else if (reward instanceof CurrencyReward c) {
                currency += c.amount();
            }
            // A loot table counts as a reward but not as a stack, as on the server.
        }
        return new Totals(rewards.size(), stacks, xp, currency);
    }

    /** "1h 30m" style text for a cooldown in seconds (pure; the units come from lang in the screen). */
    public static long[] splitDuration(long seconds) {
        long s = Math.max(0L, seconds);
        return new long[] {s / 86_400L, (s % 86_400L) / 3_600L, (s % 3_600L) / 60L};
    }
}
