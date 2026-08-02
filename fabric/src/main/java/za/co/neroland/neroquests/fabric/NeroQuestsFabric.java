package za.co.neroland.neroquests.fabric;

import net.fabricmc.api.ModInitializer;

import za.co.neroland.neroquests.NeroQuestsCommon;

/** Fabric entry point for NeroQuests. */
public final class NeroQuestsFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Fabric bootstrap");
        // Common init declares the payloads; the registration below consumes that declaration.
        NeroQuestsCommon.init();
        FabricQuestNetwork.registerCommon();
        // Objective triggers Fabric alone can provide (server tick, kill credit, join sync).
        FabricQuestEvents.register();
    }
}
