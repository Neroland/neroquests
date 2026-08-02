package za.co.neroland.neroquests.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;

import za.co.neroland.nerolandcore.registry.CoreCreativeTab;
import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.item.QuestBookItem;

/**
 * NeroQuests' item registrations.
 *
 * <p>Registration rides on <b>Neroland Core's</b> {@code RegistrationProvider} seam rather than a
 * NeroQuests-specific one: the seam takes the mod id as a parameter, is published API on Core (which
 * every Nero mod already hard-depends on and which loads first), and ships one ServiceLoader impl
 * per loader inside Core's own jars — so there is nothing to duplicate here.
 *
 * <p>On Fabric that impl registers <em>eagerly</em>, at the moment this class is initialised, which
 * is why {@link #init()} exists. On NeoForge and Forge it creates a {@code DeferredRegister} that is
 * attached to the mod event bus by the {@code RegistrationProvider.attach(...)} call in each of
 * those loaders' entry points.
 */
public final class ModItems {

    public static final RegistrationProvider<Item> ITEMS =
            RegistrationProvider.get(Registries.ITEM, NeroQuestsCommon.MOD_ID);

    /** {@code neroquests:quest_book} — opens the quest book screen when used. */
    public static final RegistryEntry<Item> QUEST_BOOK = ITEMS.register("quest_book",
            key -> new QuestBookItem(new Item.Properties().stacksTo(1).setId(key)));

    private ModItems() {
    }

    /** Forces this class to initialise (and therefore its items to register on Fabric). */
    public static void init() {
    }

    /**
     * Contributes the book to Core's shared creative tab. NeroQuests adds no tab of its own — the
     * whole ecosystem shares Core's. The supplier is lazy on purpose: on the deferred loaders the
     * item does not exist yet when this runs.
     */
    public static void addToCreativeTab() {
        CoreCreativeTab.add(QUEST_BOOK::get);
    }
}
