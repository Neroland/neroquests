package za.co.neroland.neroquests.platform;

import java.util.List;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * Forge implementation of {@link PlatformInfo}. Registered via
 * {@code META-INF/services/za.co.neroland.neroquests.platform.PlatformInfo}.
 *
 * <p>Forge diverges from NeoForge here: {@code FMLEnvironment.production} / {@code .dist} are
 * fields (not methods), {@code ModList.isLoaded} is static, and there is no NeoForge-style
 * {@code ModList.get().getModContainerById(...)} shape — the instance lookup is reached through
 * the static {@code ModList.getModContainerById} / {@code ModList.getMods()} pair instead.
 */
public final class ForgePlatformInfo implements PlatformInfo {

    @Override
    public String getPlatformName() {
        return "Forge";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public String getModVersion() {
        return ModList.getModContainerById(NeroQuestsCommon.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public List<String> getLoadedModIds() {
        return ModList.getMods().stream()
                .map(m -> m.getModId() + " " + m.getVersion())
                .sorted()
                .toList();
    }
}
