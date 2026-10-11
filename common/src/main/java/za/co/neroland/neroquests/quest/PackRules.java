package za.co.neroland.neroquests.quest;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.reward.ModHookReward;

/**
 * The per-definition rules a quest pack must follow (B2), as pure functions so the loader, the tests
 * and {@code QuestPackValidator} apply exactly the same checks. Each returns a short operator-facing
 * reason (resource ids and plain words only) or empty. Breaking any of them drops the definition with
 * that reason in {@code /neroquests reload-check}; nothing ever crashes.
 *
 * <p>Applies to {@link QuestSource#PACK} definitions; {@link #modHook} applies to every source. The
 * {@code gate}/{@code reputation} reward rule lives in
 * {@link QuestDefinitions#restrictedRewardOutsideNamespace} (AUD-23) and runs first.
 */
public final class PackRules {

    /**
     * A translation key: lower-case letters, digits, {@code _ . -}, at least one dot, no spaces. The
     * recommended shape is {@code quest.<ns>.<path with / as .>.title|desc} and
     * {@code chapter.<ns>.<path>.title} — see {@link #recommendedKey} — but any key-shaped string is
     * accepted. What is refused is a literal sentence, which would ship untranslated.
     */
    private static final Pattern KEY = Pattern.compile("[a-z0-9_\\-]+(\\.[a-z0-9_\\-]+)+");

    private PackRules() {
    }

    /** The first rule {@code quest} breaks, or empty. {@code chapters} are the candidate chapters of the load. */
    public static Optional<String> violation(Quest quest, Map<Identifier, Chapter> chapters) {
        Optional<String> hook = modHook(quest);
        if (hook.isPresent() || quest.source() != QuestSource.PACK) {
            return hook;
        }
        Optional<String> reason = crossNamespaceReference(quest);
        if (reason.isEmpty()) {
            reason = serverScopeWithoutOptIn(quest, chapters);
        }
        if (reason.isEmpty()) {
            reason = literalText(quest);
        }
        return reason;
    }

    /** The first rule a pack chapter breaks (its title must be a translation key), or empty. */
    public static Optional<String> chapterViolation(Chapter chapter) {
        if (chapter.source() == QuestSource.PACK && !isKey(chapter.title())) {
            return Optional.of("chapter title must be a translation key (e.g. "
                    + recommendedKey("chapter", chapter.id(), "title") + "), not literal text");
        }
        return Optional.empty();
    }

    /**
     * Whether {@code chapter} may list {@code quest}: a pack chapter only quests of its own namespace, a custom
     * chapter only custom quests, and no spine or pack chapter a custom quest (D1).
     */
    public static boolean mayList(Chapter chapter, Identifier quest) {
        QuestSource listed = QuestSource.of(quest);
        return switch (chapter.source()) {
            case PACK -> chapter.id().getNamespace().equals(quest.getNamespace());
            case CUSTOM -> listed == QuestSource.CUSTOM;
            case SPINE -> listed != QuestSource.CUSTOM;
        };
    }

    /** {@code namespace} is reserved for the editor store and may not come from a datapack. */
    public static boolean reservedNamespace(Identifier id) {
        return QuestSource.CUSTOM_NAMESPACE.equals(id.getNamespace());
    }

    /**
     * A pack may depend only on quests of its own namespace or of the spine — as a prerequisite or a
     * {@code quest_complete} objective. Cross-pack references would make one sibling's pack depend on
     * another's being installed.
     */
    public static Optional<String> crossNamespaceReference(Quest quest) {
        for (Identifier prerequisite : quest.prerequisites()) {
            if (!allowedReference(quest, prerequisite)) {
                return Optional.of("prerequisite " + prerequisite + " is outside the pack's namespace '"
                        + quest.id().getNamespace() + "' and the '" + NeroQuestsCommon.MOD_ID + "' spine");
            }
        }
        for (ObjectiveSpec objective : quest.objectives()) {
            if (objective instanceof QuestCompleteObjective complete && !allowedReference(quest, complete.quest())) {
                return Optional.of("quest_complete objective names " + complete.quest()
                        + ", outside the pack's namespace '" + quest.id().getNamespace() + "' and the '"
                        + NeroQuestsCommon.MOD_ID + "' spine");
            }
        }
        return Optional.empty();
    }

    private static boolean allowedReference(Quest quest, Identifier target) {
        String namespace = target.getNamespace();
        return namespace.equals(quest.id().getNamespace()) || NeroQuestsCommon.MOD_ID.equals(namespace);
    }

    /**
     * A pack quest may use {@code "scope": "server"} only when a chapter of its own namespace lists it
     * and sets {@code "allow_server_scope": true}.
     */
    public static Optional<String> serverScopeWithoutOptIn(Quest quest, Map<Identifier, Chapter> chapters) {
        if (quest.scope() != QuestScope.SERVER) {
            return Optional.empty();
        }
        for (Chapter chapter : chapters.values()) {
            if (chapter.allowServerScope()
                    && chapter.id().getNamespace().equals(quest.id().getNamespace())
                    && chapter.entry(quest.id()).isPresent()) {
                return Optional.empty();
            }
        }
        return Optional.of("\"scope\": \"server\" needs a chapter of the same namespace that lists this quest and "
                + "sets \"allow_server_scope\": true");
    }

    /** Title and description (when given) must be translation keys, not literal text. */
    public static Optional<String> literalText(Quest quest) {
        if (!isKey(quest.title())) {
            return Optional.of("title must be a translation key (e.g. " + recommendedKey("quest", quest.id(), "title")
                    + "), not literal text");
        }
        if (!quest.description().isEmpty() && !isKey(quest.description())) {
            return Optional.of("description must be a translation key (e.g. "
                    + recommendedKey("quest", quest.id(), "desc") + "), not literal text");
        }
        return Optional.empty();
    }

    /** Every {@code mod_hook} reward's gate must be a {@code hook/} gate in the quest's own namespace. */
    public static Optional<String> modHook(Quest quest) {
        for (RewardSpec reward : quest.rewards()) {
            if (reward instanceof ModHookReward hook) {
                Optional<String> reason = ModHookReward.invalidFor(quest.id(), hook.gate());
                if (reason.isPresent()) {
                    return reason;
                }
            }
        }
        return Optional.empty();
    }

    /** Whether {@code text} is shaped like a translation key. */
    public static boolean isKey(String text) {
        return text != null && KEY.matcher(text).matches();
    }

    /** {@code <kind>.<ns>.<path with / as .>.<suffix>} — the recommended key for a definition. */
    public static String recommendedKey(String kind, Identifier id, String suffix) {
        return kind + "." + id.getNamespace() + "." + id.getPath().replace('/', '.') + "." + suffix;
    }
}
