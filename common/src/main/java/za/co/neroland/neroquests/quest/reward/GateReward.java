package za.co.neroland.neroquests.quest.reward;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

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
 * <p><b>Scope decides the path.</b> A {@code scope: player} quest calls
 * {@link ProgressionGates#tryOpen(ServerPlayer, Identifier)} for the completing player; a
 * {@code scope: server} quest calls
 * {@link ProgressionGates#setServerGate(net.minecraft.server.MinecraftServer, Identifier, boolean)}
 * so the whole world advances at once.
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
        if (context.scope() == QuestScope.SERVER) {
            ProgressionGates.setServerGate(context.server(), gate, true);
            return;
        }
        ServerPlayer player = context.player();
        if (player == null) {
            NeroQuestsCommon.LOGGER.debug(
                    "[NeroQuests] Quest {} reward {} ({}) skipped: the recipient is offline.",
                    context.quest().id(), TYPE_ID, gate);
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
