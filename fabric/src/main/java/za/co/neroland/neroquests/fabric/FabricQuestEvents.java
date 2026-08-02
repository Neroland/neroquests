package za.co.neroland.neroquests.fabric;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import za.co.neroland.neroquests.command.QuestCommands;
import za.co.neroland.neroquests.quest.engine.QuestTriggers;

/**
 * Fabric side of the objective triggers: translates Fabric API events into
 * {@link QuestTriggers} calls.
 *
 * <p>Only two hooks are needed here. Progression gates arrive through Neroland Core's own
 * loader-agnostic bus (wired in common init), crafting is caught by the shared
 * {@code ItemStackMixin}, and dimension/inventory objectives are measured by the periodic sweep
 * rather than evented.
 */
public final class FabricQuestEvents {

    private FabricQuestEvents() {
    }

    /** Called once from the Fabric entry point. */
    public static void register() {
        // Periodic re-measure of collect / dimension / gate objectives (self-throttled to 1 Hz).
        ServerTickEvents.END_SERVER_TICK.register(QuestTriggers::serverTick);

        // Kill credit. AFTER_DEATH is server-side only, and hands over exactly the pair the
        // trigger wants: the victim plus the damage source the credit is resolved from.
        ServerLivingEntityEvents.AFTER_DEATH.register(QuestTriggers::entityKilled);

        // Push quest definitions + the player's own progress as they join. JOIN fires once the
        // connection can carry custom payloads, which is what this needs.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                QuestTriggers.playerJoined(handler.player));

        // The /neroquests admin tree; the tree itself is loader-agnostic, and neither the build
        // context nor the dedicated/integrated selection changes it.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                QuestCommands.register(dispatcher));
    }
}
