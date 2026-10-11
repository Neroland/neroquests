package za.co.neroland.neroquests.quest.objective;

import java.util.Locale;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.engine.EconomyActivity;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/**
 * Objective {@code neroquests:economy} — trade on a server economy: sell or buy goods, or earn
 * credits from other mods' payouts.
 *
 * <pre>{@code { "type": "neroquests:economy", "event": "sold", "tag": "c:ingots/iron", "count": 64 }}
 * {@code { "type": "neroquests:economy", "event": "earned", "count": 500 }}</pre>
 *
 * <ul>
 *   <li>{@code event} (required) — {@code sold}, {@code bought} or {@code earned}.</li>
 *   <li>{@code item} / {@code tag} (optional, at most one) — only goods matching it count; omit both
 *       to count any item. Ignored for {@code earned}.</li>
 *   <li>{@code count} — units sold/bought, or credits earned (default 1).</li>
 * </ul>
 *
 * <p>Fed by an optional economy mod's events (NeroEconomy). Counted, never measured, so progress
 * never falls. Credits paid by NeroQuests itself never count as {@code earned}, or a quest reward
 * would complete an "earn N" quest on its own. Without an economy mod the objective loads fine and
 * simply never advances — it is deliberately <em>not</em> treated as missing content, so it cannot
 * be skipped or auto-completed by {@code missingModObjectivePolicy}. Gate such quests behind an
 * economy milestone ({@code visible_gate}) so they stay hidden when there is no economy.
 *
 * <p>Only an online player can be credited: an event for an offline player (a resting order that
 * filled while they were away) is not stored anywhere and does not count.
 */
public record EconomyObjective(Event event, Optional<Identifier> item, Optional<Identifier> tag, int count)
        implements ObjectiveSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "economy");

    public static final MapCodec<EconomyObjective> CODEC = RecordCodecBuilder.<EconomyObjective>mapCodec(inst -> inst.group(
            Event.CODEC.fieldOf("event").forGetter(EconomyObjective::event),
            Identifier.CODEC.optionalFieldOf("item").forGetter(EconomyObjective::item),
            Identifier.CODEC.optionalFieldOf("tag").forGetter(EconomyObjective::tag),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1)
                    .forGetter(EconomyObjective::count)
    ).apply(inst, EconomyObjective::new)).validate(objective -> objective.item().isPresent() && objective.tag().isPresent()
            ? DataResult.error(() -> "an economy objective takes at most one of 'item' or 'tag'")
            : DataResult.success(objective));

    /** Which economy happening counts. */
    public enum Event {
        SOLD,
        BOUGHT,
        EARNED;

        public static final Codec<Event> CODEC = Codec.STRING.comapFlatMap(
                Event::parse, event -> event.name().toLowerCase(Locale.ROOT));

        private static DataResult<Event> parse(String name) {
            try {
                return DataResult.success(valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                return DataResult.error(() -> "Unknown economy event '" + name
                        + "'; expected one of sold, bought, earned");
            }
        }

        boolean accepts(EconomyActivity.Kind kind) {
            return switch (this) {
                case SOLD -> kind == EconomyActivity.Kind.SOLD;
                case BOUGHT -> kind == EconomyActivity.Kind.BOUGHT;
                case EARNED -> kind == EconomyActivity.Kind.EARNED;
            };
        }
    }

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public int target() {
        return count;
    }

    @Override
    public int creditEconomy(EconomyActivity activity, ObjectiveContext context) {
        if (activity == null || activity.amount() <= 0L || !event.accepts(activity.kind())) {
            return 0;
        }
        if (event != Event.EARNED && !matchesItem(activity.itemId())) {
            return 0;
        }
        return (int) Math.min(activity.amount(), Integer.MAX_VALUE);
    }

    /** Whether the traded item satisfies the optional {@code item} / {@code tag} filter. */
    private boolean matchesItem(String itemId) {
        if (item.isEmpty() && tag.isEmpty()) {
            return true;
        }
        Identifier traded = itemId == null ? null : Identifier.tryParse(itemId);
        if (traded == null) {
            return false;
        }
        if (item.isPresent()) {
            return item.get().equals(traded);
        }
        if (!BuiltInRegistries.ITEM.containsKey(traded)) {
            return false;
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(traded))
                .is(TagKey.create(Registries.ITEM, tag.get()));
    }

    @Override
    public String contentLabel() {
        return TYPE_ID + "/" + event.name().toLowerCase(Locale.ROOT);
    }
}
