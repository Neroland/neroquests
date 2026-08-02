package za.co.neroland.neroquests.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.client.screen.QuestBookScreen;

/**
 * The quest book key binding — <b>G</b> by default.
 *
 * <p>Client-only: this class is referenced solely from the three loaders' client entry points, so a
 * dedicated server never loads it.
 *
 * <p>26.x note: key-binding categories are no longer free-form strings. {@link KeyMapping.Category}
 * is a record around an {@link Identifier}, created once through
 * {@link KeyMapping.Category#register(Identifier)} — a second registration of the same id throws.
 * Doing it in this class's static initialiser guarantees exactly one call per JVM on every loader,
 * so no loader-specific category registration is needed. The category's display name comes from
 * {@code Category#label()}, which is {@code id.toLanguageKey("key.category")} — hence the
 * {@code key.category.neroquests.quests} lang entry.
 */
public final class QuestBookKeys {

    /** The category id; its label key is {@code key.category.neroquests.quests}. */
    public static final Identifier CATEGORY_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "quests");

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(CATEGORY_ID);

    /** Opens the quest book. Default: G. */
    public static final KeyMapping OPEN_QUEST_BOOK =
            new KeyMapping("key.neroquests.open_quest_book", InputConstants.KEY_G, CATEGORY);

    private QuestBookKeys() {
    }

    /**
     * Drains any queued presses and opens the book. Called from each loader's client tick hook —
     * {@code consumeClick()} is the only correct way to read a binding outside a screen, because it
     * pops one queued press at a time rather than reporting a held key.
     */
    public static void tick() {
        while (OPEN_QUEST_BOOK.consumeClick()) {
            QuestBookScreen.open();
        }
    }
}
