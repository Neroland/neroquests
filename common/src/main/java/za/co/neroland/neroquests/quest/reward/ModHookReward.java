package za.co.neroland.neroquests.quest.reward;

import java.util.Optional;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerolandcore.progression.Gate;
import za.co.neroland.nerolandcore.progression.GateDefinitions;
import za.co.neroland.nerolandcore.progression.GateScope;
import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Reward {@code neroquests:mod_hook} (C3) — the way a sibling mod reacts to "this player finished my
 * quest" without either mod depending on the other.
 *
 * <pre>{@code { "type": "neroquests:mod_hook", "gate": "nerospace:hook/star_guide_page" }}</pre>
 *
 * <p>The sibling ships a <b>record gate</b> in its own jar
 * ({@code data/<ns>/neroland_gates/hook/<id>.json}, {@code "scope": "player"}, no {@code requires})
 * that gates nothing. On grant NeroQuests opens that gate for the recipient through Core's
 * {@link ProgressionGates#open(net.minecraft.server.MinecraftServer, java.util.UUID, Identifier)}
 * (works for an offline recipient too). The sibling listens with Core's {@code CoreEvents.onProgression}
 * / {@code GateEvents.onChange} — the change's {@code target} is the player's UUID string — applies its
 * effect, and may {@code close} the gate again to re-arm it.
 *
 * <p><b>Rules (validated at load):</b> the gate must be in the quest's own namespace and its path must
 * start with {@code hook/}; editor-made ({@code custom:}) quests may not use this reward. At grant: an
 * undefined gate, a gate that is not player-scope, or one with {@code requires} is reported once and
 * nothing is written.
 *
 * <p><b>Why this ignores {@code gateWritesEnabled}:</b> that switch exists so NeroQuests never moves
 * ecosystem progression. A hook gate is a record that gates nothing — siblings must <b>never</b> check
 * a hook gate for access to anything — so writing it is allowed even when gate writes are off.
 */
public record ModHookReward(Identifier gate) implements RewardSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "mod_hook");

    /** Every hook gate's path starts with this. */
    public static final String HOOK_PATH_PREFIX = "hook/";

    public static final MapCodec<ModHookReward> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.fieldOf("gate").forGetter(ModHookReward::gate)
    ).apply(inst, ModHookReward::new));

    /**
     * Why {@code gate} may not be a hook of the quest {@code quest}, or empty when it may (pure; used by
     * load validation and the pack validator).
     */
    public static Optional<String> invalidFor(Identifier quest, Identifier gate) {
        if (QuestSource.of(quest) == QuestSource.CUSTOM) {
            return Optional.of("mod_hook rewards are not available to editor-made quests");
        }
        if (!quest.getNamespace().equals(gate.getNamespace())) {
            return Optional.of("mod_hook gate " + gate + " must be in the quest's own namespace '"
                    + quest.getNamespace() + "'");
        }
        if (!gate.getPath().startsWith(HOOK_PATH_PREFIX) || gate.getPath().length() == HOOK_PATH_PREFIX.length()) {
            return Optional.of("mod_hook gate " + gate + " must have a path starting with '" + HOOK_PATH_PREFIX + "'");
        }
        return Optional.empty();
    }

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public void grant(RewardContext context) {
        Gate definition = GateDefinitions.forServer(context.server()).get(gate);
        if (definition == null) {
            RewardLog.warnOnce("hook-undefined:" + gate,
                    "[NeroQuests] Quest {} has mod_hook gate '{}', which no gate definition declares (is its mod "
                            + "installed?); nothing is written for it.", context.quest().id(), gate);
            return;
        }
        if (definition.scope() != GateScope.PLAYER || !definition.requires().isEmpty()) {
            RewardLog.warnOnce("hook-shape:" + gate,
                    "[NeroQuests] mod_hook gate '{}' must be a player-scope record gate with no requires; "
                            + "nothing is written for it.", gate);
            return;
        }
        if (!ProgressionGates.open(context.server(), context.playerId(), gate)) {
            NeroQuestsCommon.LOGGER.debug("[NeroQuests] mod_hook gate '{}' of quest {} was already open.",
                    gate, context.quest().id());
        }
    }
}
