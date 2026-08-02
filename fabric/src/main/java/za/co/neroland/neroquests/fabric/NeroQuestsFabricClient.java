package za.co.neroland.neroquests.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.client.QuestBookKeys;
import za.co.neroland.neroquests.client.QuestBookOpener;
import za.co.neroland.neroquests.client.screen.QuestBookScreen;
import za.co.neroland.neroquests.network.QuestNetwork;

/** Fabric client entry point for NeroQuests. */
public final class NeroQuestsFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Fabric client bootstrap");
        // Clientbound receivers (client-only API) — registered here, off the dedicated server.
        FabricQuestNetwork.registerClient();

        // Let the quest book item reach the client-only screen. Installed on the client only, so
        // common code stays free of any client reference.
        QuestBookOpener.setOpener(QuestBookScreen::open);

        // The open-book key binding (G by default). 26.x renamed Fabric's module: the helper is
        // KeyMappingHelper in ...client.keymapping.v1, not the old KeyBindingHelper.
        KeyMappingHelper.registerKeyMapping(QuestBookKeys.OPEN_QUEST_BOOK);
        ClientTickEvents.END_CLIENT_TICK.register(client -> QuestBookKeys.tick());

        // Drop the synced mirror caches on leaving a world/server, so one session's quests can
        // never be shown in the next (or on a server that does not run NeroQuests).
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                QuestNetwork.clearClientCaches());
    }
}
