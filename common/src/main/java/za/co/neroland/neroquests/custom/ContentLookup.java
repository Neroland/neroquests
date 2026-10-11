package za.co.neroland.neroquests.custom;

import net.minecraft.resources.Identifier;

/**
 * What exists in the running game, as far as the editor's id validation needs to know (D2). The server
 * implementation is {@link ServerContentLookup}; tests supply their own, so every rule stays testable
 * without a Minecraft bootstrap.
 */
public interface ContentLookup {

    /** A registered item (never air by default). */
    boolean item(Identifier id);

    /** An item tag that resolves to at least one item. */
    boolean itemTag(Identifier id);

    /** The item's maximum stack size (only asked for registered items). */
    int maxStackSize(Identifier item);

    /** A registered entity type. */
    boolean entity(Identifier id);

    /** An entity-type tag that resolves to at least one type. */
    boolean entityTag(Identifier id);

    /** A loaded dimension. */
    boolean dimension(Identifier id);

    /** A registered structure. */
    boolean structure(Identifier id);

    /** A structure tag that resolves to at least one structure. */
    boolean structureTag(Identifier id);

    /** A defined Neroland Core gate. */
    boolean gate(Identifier id);

    /** A threshold channel: seen this session, or owned by a loaded mod. */
    boolean channel(Identifier id);

    /** A loaded quest (any source). */
    boolean quest(Identifier id);

    /** A loaded loot table. */
    boolean lootTable(Identifier id);

    /** A currency the economy knows. */
    boolean currency(Identifier id);
}
