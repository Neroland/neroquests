package za.co.neroland.neroquests.fabric;

import net.fabricmc.api.ModInitializer;

import za.co.neroland.neroquests.NeroQuestsCommon;

/** Fabric entry point for NeroQuests. */
public final class NeroQuestsFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Fabric bootstrap");
        NeroQuestsCommon.init();
    }
}
