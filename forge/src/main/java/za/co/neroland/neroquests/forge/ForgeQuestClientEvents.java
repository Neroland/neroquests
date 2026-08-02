package za.co.neroland.neroquests.forge;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;

import za.co.neroland.neroquests.client.QuestBookKeys;
import za.co.neroland.neroquests.client.QuestBookOpener;
import za.co.neroland.neroquests.client.screen.QuestBookScreen;
import za.co.neroland.neroquests.network.QuestNetwork;

/**
 * Forge client-side quest wiring. Loaded only on the physical client (gated behind
 * {@code Dist.CLIENT} in the entry point), so its client-only references never touch a dedicated
 * server. Forge 26.x has no single global event bus — each event class owns a static {@code BUS} —
 * so listeners are attached to the event types directly.
 *
 * <p>It drops the synced mirror caches when the player leaves a world or server, and wires the quest
 * book: the screen opener the book item calls into, plus the open-book key binding.
 */
public final class ForgeQuestClientEvents {

    private ForgeQuestClientEvents() {
    }

    /** Called once from the Forge entry point, on the client only. */
    public static void register() {
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> QuestNetwork.clearClientCaches());

        // Let the quest book item reach the client-only screen.
        QuestBookOpener.setOpener(QuestBookScreen::open);

        // The category is NOT registered here: KeyMapping.Category.register(Identifier) is vanilla
        // and already ran in QuestBookKeys' static initialiser (registering an id twice throws).
        RegisterKeyMappingsEvent.BUS.addListener(event -> {
            event.register(QuestBookKeys.OPEN_QUEST_BOOK);
        });
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            QuestBookKeys.tick();
        });
    }
}
