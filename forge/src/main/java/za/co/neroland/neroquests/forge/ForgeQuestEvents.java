package za.co.neroland.neroquests.forge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import za.co.neroland.neroquests.command.QuestCommands;
import za.co.neroland.neroquests.quest.engine.QuestTriggers;

/**
 * Forge side of the objective triggers. Forge 26.x has no single global event bus — each event
 * class owns a static {@code BUS} — so listeners are attached per event type.
 *
 * <p>Only two hooks are needed here. Progression gates arrive through Neroland Core's own
 * loader-agnostic bus (wired in common init); crafting is caught by the shared
 * {@code ItemStackMixin}, so Forge's {@code ItemCraftedEvent} is deliberately <b>not</b>
 * subscribed (it would count every craft twice); and dimension/inventory objectives are measured
 * by the periodic sweep rather than evented.
 */
public final class ForgeQuestEvents {

    private ForgeQuestEvents() {
    }

    /** Called once from the Forge entry point. */
    public static void register() {
        // Periodic re-measure of collect / dimension / gate objectives (self-throttled to 1 Hz).
        TickEvent.ServerTickEvent.Post.BUS.addListener(event ->
                QuestTriggers.serverTick(event.server()));

        // Kill credit; the trigger resolves the responsible player from the damage source.
        // A statement-bodied lambda is required: LivingDeathEvent is cancellable, so its BUS
        // offers both Consumer and Predicate overloads and an expression lambda is ambiguous.
        LivingDeathEvent.BUS.addListener(event -> {
            QuestTriggers.entityKilled(event.getEntity(), event.getSource());
        });

        // Push quest definitions + the player's own progress as they join.
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                QuestTriggers.playerJoined(serverPlayer);
            }
        });

        // The /neroquests admin tree; the tree itself is loader-agnostic.
        RegisterCommandsEvent.BUS.addListener(event -> QuestCommands.register(event.getDispatcher()));
    }
}
