package za.co.neroland.neroquests.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The server-side immutability guard (B1). Every editor mutation path (Stage D) asks
 * {@link #requireMutable} first and refuses with the returned message when there is one: quests and
 * chapters from the spine or from a pack can never be changed, hidden, deleted or re-rewarded.
 *
 * <p>Decided by the id alone ({@link QuestSource#of(Identifier)}), never by anything a client sends.
 */
public final class QuestSources {

    /** Translation key of the refusal for a spine definition. */
    public static final String LOCKED_SPINE = "message.neroquests.editor.locked.spine";

    /** Translation key of the refusal for a pack definition. */
    public static final String LOCKED_PACK = "message.neroquests.editor.locked.pack";

    private QuestSources() {
    }

    /** Whether an editor mutation may touch the quest or chapter with this id. */
    public static boolean isMutable(Identifier id) {
        return id != null && QuestSource.of(id).isMutable();
    }

    /**
     * Empty when {@code id} may be mutated; otherwise the translatable reason to send back. The id
     * itself is pack content (no player data), so it may appear in the message.
     */
    public static Optional<Component> requireMutable(Identifier id) {
        return lockKey(id).map(key -> Component.translatable(key, id == null ? "?" : id.toString()));
    }

    /**
     * Per-source counts for {@code /neroquests reload-check} (B5), in report order: {@code spine N}, then
     * {@code pack <namespace> N} per pack namespace (sorted), then {@code custom N}. Spine and custom are
     * always listed, even at zero.
     */
    public static List<String> summary(Collection<Quest> quests) {
        int spine = 0;
        int custom = 0;
        Map<String, Integer> packs = new TreeMap<>();
        for (Quest quest : quests) {
            switch (quest.source()) {
                case SPINE -> spine++;
                case CUSTOM -> custom++;
                case PACK -> packs.merge(quest.id().getNamespace(), Integer.valueOf(1), Integer::sum);
            }
        }
        List<String> out = new ArrayList<>(packs.size() + 2);
        out.add("spine " + spine);
        packs.forEach((namespace, count) -> out.add("pack " + namespace + " " + count));
        out.add("custom " + custom);
        return List.copyOf(out);
    }

    /** The translation key {@link #requireMutable} would use for {@code id}, or empty when mutable (pure). */
    public static Optional<String> lockKey(Identifier id) {
        if (id == null) {
            return Optional.of(LOCKED_PACK);
        }
        return switch (QuestSource.of(id)) {
            case CUSTOM -> Optional.empty();
            case SPINE -> Optional.of(LOCKED_SPINE);
            case PACK -> Optional.of(LOCKED_PACK);
        };
    }
}
