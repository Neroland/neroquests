package za.co.neroland.neroquests.quest.objective;

import java.util.Locale;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerolandcore.event.ThresholdEvents.ThresholdCrossing;
import za.co.neroland.nerolandcore.platform.Services;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/**
 * Objective {@code neroquests:custom_event} — wait for another mod to report that one of its
 * tracked quantities crossed a threshold.
 *
 * <pre>{@code { "type": "neroquests:custom_event", "channel": "nerocolonies:oxygen",
 *   "direction": "rising" }}</pre>
 *
 * <p>This is the second of NeroQuests' two zero-coupling joins into the ecosystem. Where
 * {@link GateOpenObjective} waits on a <em>player's</em> progression milestone, this waits on the
 * <em>world's</em> state: a region's pollution passing its event threshold, a colony's life support
 * failing or recovering, a boss changing phase. The publisher fires a
 * {@link ThresholdCrossing} on Neroland Core's {@code ThresholdEvents} bus and NeroQuests listens;
 * neither mod imports the other, and both depend only on Core.
 *
 * <h2>What a crossing carries</h2>
 *
 * <p>Core's payload is deliberately small — {@code channel}, {@code scope}, {@code value},
 * {@code threshold} and {@code rising} — and this objective matches on exactly those fields, never
 * on anything Core cannot supply:
 *
 * <ul>
 *   <li>{@code channel} (required) — the quantity, by convention {@code <modid>:<channel>}. The
 *       namespace is what {@link #contentPresent} tests, so the convention is load-bearing rather
 *       than cosmetic.</li>
 *   <li>{@code event_scope} — an exact-match filter on the crossing's scope key, which names
 *       <em>where</em> it crossed (a colony id, a packed region key, a dimension id). Named
 *       {@code event_scope} and not {@code scope} so it can never be confused with the quest's own
 *       {@code scope} field. Omit it to accept a crossing from anywhere.</li>
 *   <li>{@code direction} — {@code rising}, {@code falling} or {@code any} (the default).
 *       <b>Rising is not a synonym for "good":</b> Core defines it as "the value crossed upward",
 *       which is recovery on {@code nerocolonies:oxygen} and worsening on
 *       {@code nerotech:pollution}. Read the publisher's channel before assuming.</li>
 *   <li>{@code min_value} / {@code max_value} — inclusive bounds on the crossing's value, which is
 *       how a quest says "the colony's third structure" rather than "any structure".</li>
 *   <li>{@code count} — how many matching crossings are needed (default 1).</li>
 *   <li>{@code audience} — see below.</li>
 * </ul>
 *
 * <h2>Broadcast versus player-scoped ({@code audience})</h2>
 *
 * <p>A crossing names a <b>place or system and never a person</b> (Core's contract, for POPIA/GDPR
 * reasons), so there is no player to credit and the trigger has to fan out. Silently crediting every
 * player online would let one colony's news complete a personal quest for someone who was asleep on
 * the other side of the world, so the quest author has to say which they meant:
 *
 * <ul>
 *   <li>{@link Audience#WORLD} (the default) — the crossing moves the quest's <b>shared</b> counter
 *       exactly once, however many players witnessed it. Shared counters only exist on a
 *       {@code "scope": "server"} quest, so that is where this belongs; on a player-scoped quest the
 *       objective could never advance, and {@link #unusableInScope} says so at load time rather than
 *       leaving a quest stuck forever.</li>
 *   <li>{@link Audience#EVERYONE} — the opt-in broadcast: one crossing credits <b>every online
 *       player</b> whose copy of the quest is available and unfinished. Players who are offline at
 *       that moment miss it, exactly as they would miss a kill.</li>
 * </ul>
 *
 * <p>Counted, never measured: a crossing is an event, and there is no way to re-derive from the
 * world how many times it has already happened. The tally therefore never falls.
 *
 * <h2>Missing content</h2>
 *
 * <p>A channel whose publishing mod is not installed can never fire, so the objective degrades under
 * {@code missingModObjectivePolicy} — {@code skip} or {@code autocomplete} — exactly like an unknown
 * item id or an unloaded dimension, instead of blocking the quest forever.
 */
public record CustomEventObjective(Identifier channel,
                                   Optional<String> eventScope,
                                   Direction direction,
                                   Optional<Long> minValue,
                                   Optional<Long> maxValue,
                                   Audience audience,
                                   int count) implements ObjectiveSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "custom_event");

    public static final MapCodec<CustomEventObjective> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.fieldOf("channel").forGetter(CustomEventObjective::channel),
            Codec.STRING.optionalFieldOf("event_scope").forGetter(CustomEventObjective::eventScope),
            Direction.CODEC.optionalFieldOf("direction", Direction.ANY)
                    .forGetter(CustomEventObjective::direction),
            Codec.LONG.optionalFieldOf("min_value").forGetter(CustomEventObjective::minValue),
            Codec.LONG.optionalFieldOf("max_value").forGetter(CustomEventObjective::maxValue),
            Audience.CODEC.optionalFieldOf("audience", Audience.WORLD)
                    .forGetter(CustomEventObjective::audience),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1)
                    .forGetter(CustomEventObjective::count)
    ).apply(inst, CustomEventObjective::new));

    /** Which way a crossing must have gone to count. */
    public enum Direction {

        /** The value crossed upward. Core's word for it is "worsening", but each channel decides. */
        RISING,

        /** The value came back down across the threshold. */
        FALLING,

        /** Either direction counts. */
        ANY;

        public static final Codec<Direction> CODEC = Codec.STRING.comapFlatMap(
                Direction::parse, direction -> direction.name().toLowerCase(Locale.ROOT));

        private static DataResult<Direction> parse(String name) {
            try {
                return DataResult.success(valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                return DataResult.error(() -> "Unknown custom_event direction '" + name
                        + "'; expected one of rising, falling, any");
            }
        }

        /** Whether a crossing that went this way counts. */
        boolean accepts(boolean rising) {
            return this == ANY || (this == RISING) == rising;
        }
    }

    /** Who a crossing is credited to. See the class javadoc — this is the deliberate part. */
    public enum Audience {

        /**
         * World news: one crossing, one increment of the quest's shared counter. Only meaningful on
         * a {@code "scope": "server"} quest.
         */
        WORLD,

        /** Opt-in broadcast: one crossing credits every online player working on the quest. */
        EVERYONE;

        public static final Codec<Audience> CODEC = Codec.STRING.comapFlatMap(
                Audience::parse, audience -> audience.name().toLowerCase(Locale.ROOT));

        private static DataResult<Audience> parse(String name) {
            try {
                return DataResult.success(valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                return DataResult.error(() -> "Unknown custom_event audience '" + name
                        + "'; expected one of world, everyone");
            }
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
    public int creditThreshold(ThresholdCrossing crossing, ObjectiveContext context) {
        // A 'world' audience writes shared progress, which only a server-scoped quest has. Refusing
        // here as well as at load time means a definition that somehow slipped past validation still
        // cannot credit every player by accident.
        if (audience == Audience.WORLD && context.scope() != QuestScope.SERVER) {
            return 0;
        }
        return matches(crossing) ? 1 : 0;
    }

    @Override
    public Optional<String> unusableInScope(QuestScope scope) {
        if (audience == Audience.WORLD && scope != QuestScope.SERVER) {
            return Optional.of("custom_event on channel " + channel + " uses the default audience "
                    + "'world', which advances only the shared counter of a \"scope\": \"server\" "
                    + "quest; either give the quest server scope or declare "
                    + "\"audience\": \"everyone\"");
        }
        return Optional.empty();
    }

    /**
     * Whether the mod that publishes this channel is installed. The {@code <modid>:<channel>}
     * convention is what makes this answerable: a channel nobody publishes can never fire, and
     * treating it as missing content is what stops the quest from being stuck forever.
     */
    @Override
    public boolean contentPresent(ObjectiveContext context) {
        return Services.PLATFORM.isModLoaded(channel.getNamespace());
    }

    @Override
    public String contentLabel() {
        return channel.toString();
    }

    /** Whether one crossing satisfies every filter this objective declares. */
    private boolean matches(ThresholdCrossing crossing) {
        if (crossing == null || !channel.equals(crossing.channel())) {
            return false;
        }
        if (!direction.accepts(crossing.rising())) {
            return false;
        }
        if (eventScope.isPresent() && !eventScope.get().equals(crossing.scope())) {
            return false;
        }
        if (minValue.isPresent() && crossing.value() < minValue.get().longValue()) {
            return false;
        }
        return maxValue.isEmpty() || crossing.value() <= maxValue.get().longValue();
    }
}
