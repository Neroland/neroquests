package za.co.neroland.neroquests.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;

import za.co.neroland.neroquests.NeroQuestsCommon;

/** NeoForge entry point for NeroQuests. */
@Mod(NeroQuestsCommon.MOD_ID)
public final class NeroQuestsNeoForge {

    public NeroQuestsNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeroQuestsCommon.LOGGER.info("[NeroQuests] NeoForge bootstrap");
        // Common init declares the payloads; the registration below consumes that declaration.
        NeroQuestsCommon.init();
        // Common init created NeroQuests' DeferredRegisters through Core's registration seam; this
        // attaches them to OUR mod event bus. It must follow init and precede nothing else.
        RegistrationProvider.attach(modEventBus);
        NeoForgeQuestNetwork.register(modEventBus);
        // Objective triggers NeoForge alone can provide (server tick, kill credit, join sync).
        NeoForgeQuestEvents.register();
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            NeoForgeQuestClientEvents.register(modEventBus);
        }
    }
}
