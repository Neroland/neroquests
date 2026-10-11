package za.co.neroland.neroquests.quest;

import java.util.Locale;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * Where a quest or chapter came from, resolved from its id's namespace at load (B1):
 *
 * <ul>
 *   <li>{@link #SPINE} — namespace {@code neroquests}: NeroQuests' own story spine;</li>
 *   <li>{@link #PACK} — any other datapack namespace: a sibling mod's quest pack shipped in its own
 *       jar, or a server's datapack;</li>
 *   <li>{@link #CUSTOM} — namespace {@value #CUSTOM_NAMESPACE}: reserved for the in-game editor's
 *       store. A datapack can never claim it — such files are dropped at load.</li>
 * </ul>
 *
 * <p>{@code SPINE} and {@code PACK} are immutable at runtime: no editor mutation may touch them
 * ({@link QuestSources#requireMutable}). Because the datapack loader refuses the {@code custom}
 * namespace, deriving the source from the namespace is sound.
 */
public enum QuestSource {

    /** NeroQuests' own quests ({@code neroquests:}). */
    SPINE,

    /** A sibling mod's or a server datapack's quests (any other datapack namespace). */
    PACK,

    /** The editor store ({@code custom:}). */
    CUSTOM;

    /** The namespace reserved for editor-made quests and chapters. */
    public static final String CUSTOM_NAMESPACE = "custom";

    /** The source of a definition in {@code namespace}. */
    public static QuestSource of(String namespace) {
        if (NeroQuestsCommon.MOD_ID.equals(namespace)) {
            return SPINE;
        }
        return CUSTOM_NAMESPACE.equals(namespace) ? CUSTOM : PACK;
    }

    /** The source of the definition with this id. */
    public static QuestSource of(Identifier id) {
        return of(id.getNamespace());
    }

    /** Whether editor mutations may change definitions of this source (only {@link #CUSTOM}). */
    public boolean isMutable() {
        return this == CUSTOM;
    }

    /** Lower-case name, as shown in reports and synced views ({@code spine}, {@code pack}, {@code custom}). */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
