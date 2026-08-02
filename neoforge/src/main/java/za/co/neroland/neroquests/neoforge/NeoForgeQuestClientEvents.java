package za.co.neroland.neroquests.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

import za.co.neroland.neroquests.client.QuestBookKeys;
import za.co.neroland.neroquests.client.QuestBookOpener;
import za.co.neroland.neroquests.client.screen.QuestBookScreen;
import za.co.neroland.neroquests.network.QuestNetwork;

/**
 * NeoForge client-side quest wiring. Loaded only on the physical client (gated behind
 * {@code Dist.CLIENT} in the entry point), so its client-only references never touch a dedicated
 * server.
 *
 * <p>It drops the synced mirror caches when the player leaves a world or server — so quests from one
 * session can never be shown in the next, or on a server that does not run NeroQuests — and wires
 * the quest book: the screen opener the book item calls into, plus the open-book key binding.
 */
public final class NeoForgeQuestClientEvents {

    private NeoForgeQuestClientEvents() {
    }

    /** Called once from the NeoForge entry point, on the client only. */
    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                QuestNetwork.clearClientCaches());

        // Let the quest book item reach the client-only screen.
        QuestBookOpener.setOpener(QuestBookScreen::open);

        // Key bindings are a mod-bus registration. The category is NOT registered here: on 26.x
        // KeyMapping.Category.register(Identifier) is vanilla and already ran in QuestBookKeys'
        // static initialiser, and registering the same id twice throws.
        modEventBus.addListener((RegisterKeyMappingsEvent event) ->
                event.register(QuestBookKeys.OPEN_QUEST_BOOK));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> QuestBookKeys.tick());
    }
}
