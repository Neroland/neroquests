package za.co.neroland.neroquests.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

import za.co.neroland.neroquests.NeroQuestsCommon;

/** NeoForge entry point for NeroQuests. */
@Mod(NeroQuestsCommon.MOD_ID)
public final class NeroQuestsNeoForge {

    public NeroQuestsNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeroQuestsCommon.LOGGER.info("[NeroQuests] NeoForge bootstrap");
        NeroQuestsCommon.init();
    }
}
