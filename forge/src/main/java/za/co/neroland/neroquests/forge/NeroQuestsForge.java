package za.co.neroland.neroquests.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import za.co.neroland.neroquests.NeroQuestsCommon;

/** MinecraftForge entry point for NeroQuests. */
@Mod(NeroQuestsCommon.MOD_ID)
public final class NeroQuestsForge {

    public NeroQuestsForge(FMLJavaModLoadingContext context) {
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Forge bootstrap");
        NeroQuestsCommon.init();
    }
}
