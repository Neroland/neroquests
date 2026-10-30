package za.co.neroland.neroquests.forge;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
//? if <1.21.6 {
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
*///?} else if <1.21.9 {
/*import net.minecraftforge.eventbus.api.bus.BusGroup;
*///?}

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
    //? if >=1.21.9 {
    public static void register() {
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> QuestNetwork.clearClientCaches());
    //?} else if >=1.21.6 {
    /*public static void register(BusGroup modBusGroup) {
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> QuestNetwork.clearClientCaches());
    *///?} else {
    /*public static void register(IEventBus modEventBus) {
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                QuestNetwork.clearClientCaches());
    *///?}

        // Let the quest book item reach the client-only screen.
        QuestBookOpener.setOpener(QuestBookScreen::open);

        // The category is NOT registered here: KeyMapping.Category.register(Identifier) is vanilla
        // and already ran in QuestBookKeys' static initialiser (registering an id twice throws).
        //? if >=1.21.9 {
        RegisterKeyMappingsEvent.BUS.addListener(event -> {
            event.register(QuestBookKeys.OPEN_QUEST_BOOK);
        });
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            QuestBookKeys.tick();
        });
        //?} else if >=1.21.6 {
        /*// Forge 1.21.6 - 1.21.8: key mappings are a mod-bus event, reached through the mod's bus group.
        RegisterKeyMappingsEvent.getBus(modBusGroup).addListener(event -> event.register(QuestBookKeys.OPEN_QUEST_BOOK));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> QuestBookKeys.tick());
        *///?} else {
        /*modEventBus.addListener((RegisterKeyMappingsEvent event) -> event.register(QuestBookKeys.OPEN_QUEST_BOOK));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent.Post event) -> QuestBookKeys.tick());
        *///?}
    }
}
