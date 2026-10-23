package za.co.neroland.neroquests.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * NeroQuests' own creative tab, registered cross-loader via Core's {@link RegistrationProvider}
 * over the vanilla {@code CREATIVE_MODE_TAB} registry. Every Nero mod has its own tab; Core's
 * "Neroland" tab holds only Core's items.
 */
public final class ModCreativeTab {

    public static final RegistrationProvider<CreativeModeTab> TABS =
            RegistrationProvider.get(Registries.CREATIVE_MODE_TAB, NeroQuestsCommon.MOD_ID);

    // NOTE: vanilla CreativeModeTab.builder takes (Row, column); the no-arg overload and
    // withTabsBefore/After are NeoForge-only extensions, so they are avoided here (common = raw vanilla).
    public static final RegistryEntry<CreativeModeTab> NEROQUESTS = TABS.register("neroquests",
            key -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.neroquests"))
                    .icon(() -> new ItemStack(ModItems.QUEST_BOOK.get()))
                    .displayItems((params, output) -> ModItems.creativeContents().forEach(output::accept))
                    .build());

    private ModCreativeTab() {
    }

    /** Forces this class to initialise (and therefore the tab to register on Fabric). Call after {@link ModItems#init()}. */
    public static void init() {
    }
}
