package za.co.neroland.neroquests.fabric;

import net.fabricmc.api.ClientModInitializer;

import za.co.neroland.neroquests.NeroQuestsCommon;

/** Fabric client entry point for NeroQuests. */
public final class NeroQuestsFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Fabric client bootstrap");
    }
}
