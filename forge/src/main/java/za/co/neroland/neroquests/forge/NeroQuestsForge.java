package za.co.neroland.neroquests.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;

import za.co.neroland.neroquests.NeroQuestsCommon;

/** MinecraftForge entry point for NeroQuests. */
@Mod(NeroQuestsCommon.MOD_ID)
public final class NeroQuestsForge {

    public NeroQuestsForge(FMLJavaModLoadingContext context) {
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Forge bootstrap");
        // Common init declares the payloads; the channel below is sealed the moment it is built,
        // so that ordering is mandatory on Forge.
        NeroQuestsCommon.init();
        // Common init created NeroQuests' DeferredRegisters through Core's registration seam; this
        // attaches them to OUR mod bus group.
        RegistrationProvider.attach(context.getModBusGroup());
        ForgeQuestNetwork.register();
        // Objective triggers Forge alone can provide (server tick, kill credit, join sync).
        ForgeQuestEvents.register();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeQuestClientEvents.register();
        }
    }
}
