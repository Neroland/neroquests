package za.co.neroland.neroquests.platform;

import java.util.List;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * NeoForge implementation of {@link PlatformInfo}. Registered via
 * {@code META-INF/services/za.co.neroland.neroquests.platform.PlatformInfo}.
 */
public final class NeoForgePlatformInfo implements PlatformInfo {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        // 26.1.x exposes these as methods (the old `FMLEnvironment.production` / `.dist` fields are gone).
        return !FMLEnvironment.isProduction();
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.getDist() == Dist.CLIENT;
    }

    @Override
    public String getModVersion() {
        return ModList.get().getModContainerById(NeroQuestsCommon.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public List<String> getLoadedModIds() {
        return ModList.get().getMods().stream()
                .map(m -> m.getModId() + " " + m.getVersion())
                .sorted()
                .toList();
    }
}
