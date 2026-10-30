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
        //? if >=1.21.6 {
        RegistrationProvider.attach(context.getModBusGroup());
        //?} else {
        /*RegistrationProvider.attach(context.getModEventBus());
        *///?}
        ForgeQuestNetwork.register();
        // Objective triggers Forge alone can provide (server tick, kill credit, join sync).
        ForgeQuestEvents.register();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            //? if >=1.21.9 {
            ForgeQuestClientEvents.register();
            //?} else if >=1.21.6 {
            /*ForgeQuestClientEvents.register(context.getModBusGroup());
            *///?} else {
            /*ForgeQuestClientEvents.register(context.getModEventBus());
            *///?}
        }
    }
}
