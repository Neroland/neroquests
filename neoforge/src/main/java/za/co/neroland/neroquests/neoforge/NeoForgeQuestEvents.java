package za.co.neroland.neroquests.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import za.co.neroland.neroquests.command.QuestCommands;
import za.co.neroland.neroquests.quest.engine.QuestTriggers;

/**
 * NeoForge side of the objective triggers: game-bus listeners translated into
 * {@link QuestTriggers} calls.
 *
 * <p>Only two hooks are needed here. Progression gates arrive through Neroland Core's own
 * loader-agnostic bus (wired in common init); crafting is caught by the shared
 * {@code ItemStackMixin}, so NeoForge's {@code ItemCraftedEvent} is deliberately <b>not</b>
 * subscribed (it would count every craft twice); and dimension/inventory objectives are measured
 * by the periodic sweep rather than evented.
 */
public final class NeoForgeQuestEvents {

    private NeoForgeQuestEvents() {
    }

    /** Called once from the NeoForge entry point. */
    public static void register() {
        // Periodic re-measure of collect / dimension / gate objectives (self-throttled to 1 Hz).
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) ->
                QuestTriggers.serverTick(event.getServer()));

        // Kill credit; the trigger resolves the responsible player from the damage source.
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent event) ->
                QuestTriggers.entityKilled(event.getEntity(), event.getSource()));

        // Push quest definitions + the player's own progress as they join.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                QuestTriggers.playerJoined(serverPlayer);
            }
        });

        // The /neroquests admin tree; the tree itself is loader-agnostic.
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                QuestCommands.register(event.getDispatcher()));
    }
}
