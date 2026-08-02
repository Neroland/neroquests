package za.co.neroland.neroquests.platform;

import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * Fabric implementation of {@link PlatformInfo}. Registered via
 * {@code META-INF/services/za.co.neroland.neroquests.platform.PlatformInfo}.
 */
public final class FabricPlatformInfo implements PlatformInfo {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public String getModVersion() {
        return FabricLoader.getInstance().getModContainer(NeroQuestsCommon.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public List<String> getLoadedModIds() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(m -> m.getMetadata().getId() + " " + m.getMetadata().getVersion().getFriendlyString())
                .sorted()
                .toList();
    }
}
