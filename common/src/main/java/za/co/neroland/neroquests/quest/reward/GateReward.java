package za.co.neroland.neroquests.quest.reward;

import java.util.UUID;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.progression.Gate;
import za.co.neroland.nerolandcore.progression.GateDefinitions;
import za.co.neroland.nerolandcore.progression.GateScope;
import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Reward {@code neroquests:gate} — opens a Neroland Core progression gate on completion. This is
 * how a quest line drives the wider ecosystem: finish the quest, unlock the tier.
 *
 * <pre>{@code { "type": "neroquests:gate", "gate": "nerolandcore:industrial_power" }}</pre>
 *
 * <p><b>The gate's declared scope decides the path (AUD-13).</b> A gate declared {@code server} on a
 * {@code scope: server} quest is set once for the world with
 * {@link ProgressionGates#setServerGate(MinecraftServer, Identifier, boolean)} — after checking its
 * {@code requires}. Any other gate is opened per recipient in its own scope:
 * {@link ProgressionGates#tryOpen(ServerPlayer, Identifier)} for an online recipient, and for an
 * offline one {@link ProgressionGates#open(MinecraftServer, UUID, Identifier)} after the same
 * requirement check through the public API. A gate id no definition declares is reported once and
 * grants nothing.
 *
 * <p><b>Namespace rule (AUD-23).</b> Only quests of the {@code neroquests} namespace may carry this
 * reward; any other quest that does is dropped at load ({@code QuestDefinitions}).
 *
 * <p><b>The gate's own requirements stay authoritative.</b> {@code tryOpen} only opens a gate whose
 * prerequisite chain is already satisfied; when it is not, NeroQuests logs at debug and leaves the
 * gate closed. It never forces one open — a quest is allowed to <em>grant</em> progression, not to
 * bypass the progression graph Core (or a datapack) defines. Note {@code tryOpen} also reports "no
 * change" for a gate that was already open, which is a success, not a failure.
 *
 * <p><b>Kill switch.</b> When the server-authoritative config key {@code gateWritesEnabled} is
 * {@code false} this reward is a no-op across the board (logged once), so a pack can run NeroQuests
 * as pure content without it touching ecosystem progression at all.
 */
public record GateReward(Identifier gate) implements RewardSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "gate");

    public static final MapCodec<GateReward> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.fieldOf("gate").forGetter(GateReward::gate)
    ).apply(inst, GateReward::new));

    /**
     * Whether every prerequisite of {@code gate} is open for {@code playerId}, online or offline, through
     * Core's public API only (definitions + {@link ProgressionGates#isOpen(MinecraftServer, UUID, Identifier)}).
     */
    static boolean requirementsMetOffline(MinecraftServer server, UUID playerId, Identifier gate) {
        Gate definition = GateDefinitions.forServer(server).get(gate);
        if (definition == null) {
            return true;
        }
        for (Identifier required : definition.requires()) {
            if (!ProgressionGates.isOpen(server, playerId, required)) {
                return false;
            }
        }
        return true;
    }

    /**
     * On a {@code scope: server} quest, a gate whose <em>declared</em> scope is {@code server} is granted
     * once for the world (AUD-06/AUD-13); a player- or team-scope gate is granted per recipient. An
     * undefined gate is treated as world-level so it is reported once, not once per recipient.
     */
    @Override
    public boolean worldLevel(RewardContext context) {
        if (context.scope() != QuestScope.SERVER) {
            return false;
        }
        Gate definition = GateDefinitions.forServer(context.server()).get(gate);
        return definition == null || definition.scope() == GateScope.SERVER;
    }

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public void grant(RewardContext context) {
        if (!NeroQuestsConfig.GATE_WRITES_ENABLED.get()) {
            RewardLog.infoOnce("gate-writes-disabled",
                    "[NeroQuests] gateWritesEnabled=false, so '{}' rewards grant nothing; quests still "
                            + "complete and every other reward still pays out.", TYPE_ID);
            return;
        }
        Gate definition = GateDefinitions.forServer(context.server()).get(gate);
        if (definition == null) {
            // AUD-13: an id nothing defines can never be opened meaningfully; say so once, write nothing.
            RewardLog.warnOnce("gate-undefined:" + gate,
                    "[NeroQuests] Quest {} rewards gate '{}', which no gate definition declares; granting "
                            + "nothing for it.", context.quest().id(), gate);
            return;
        }
        ServerPlayer player = context.player();
        if (context.scope() == QuestScope.SERVER && definition.scope() == GateScope.SERVER) {
            // World-level: once per completion, and only when the gate's own requirements hold.
            boolean met = player != null
                    ? ProgressionGates.requirementsMet(player, gate)
                    : requirementsMetOffline(context.server(), context.playerId(), gate);
            if (!met || !ProgressionGates.setServerGate(context.server(), gate, true)) {
                NeroQuestsCommon.LOGGER.debug(
                        "[NeroQuests] Quest {} did not change server gate '{}' (already open, or its "
                                + "requirements are not met).", context.quest().id(), gate);
            }
            return;
        }
        // A player- or team-scope gate is written per recipient, in the gate's own scope — also on a
        // server-scope quest (AUD-13: never setServerGate for a gate that is not server-scope).
        if (player == null) {
            // AUD-05: an offline recipient's gate is written straight through Core's UUID API (Core
            // 1.15.0) rather than queued — but only when the gate's own requirements hold for them.
            if (!requirementsMetOffline(context.server(), context.playerId(), gate)
                    || !ProgressionGates.open(context.server(), context.playerId(), gate)) {
                NeroQuestsCommon.LOGGER.debug(
                        "[NeroQuests] Quest {} did not change gate '{}' for an offline recipient (already "
                                + "open, or its requirements are not met).", context.quest().id(), gate);
            }
            return;
        }
        if (!ProgressionGates.tryOpen(player, gate)) {
            // Either the gate's own 'requires' chain is not satisfied, or it was already open.
            // Both are fine — the gate graph, not the quest, decides. Never force it open.
            NeroQuestsCommon.LOGGER.debug(
                    "[NeroQuests] Quest {} did not change gate '{}' (already open, or its requirements "
                            + "are not met).", context.quest().id(), gate);
        }
    }
}
