package za.co.neroland.neroquests.quest.engine;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.quest.ObjectiveSpec;

/**
 * Cross-mod degradation: what to do with an objective that names content this installation does
 * not have — an unregistered item or entity id, a tag nothing fills, a dimension whose mod is
 * absent, a quest that has been removed from the pack.
 *
 * <p>Without this, shipping one modpack quest file to a smaller instance would leave a quest
 * permanently uncompletable. The behaviour is the server-authoritative
 * {@code missingModObjectivePolicy} config key:
 *
 * <ul>
 *   <li><b>{@code skip}</b> (default) — the objective is ignored: it neither blocks the quest nor
 *       shows progress. Effectively a target of zero.</li>
 *   <li><b>{@code autocomplete}</b> — the objective counts as already done and its counter is
 *       written up to its target, so the quest book shows it ticked.</li>
 * </ul>
 *
 * <p>Either way the quest stays completable. The offending objective is logged once per server
 * run, with resource ids only — never player data.
 */
public final class MissingContent {

    /** What an unresolvable objective does. */
    public enum Policy {
        /** Ignore the objective entirely (treat it as target 0). */
        SKIP,
        /** Treat the objective as satisfied and write its counter up to target. */
        AUTOCOMPLETE
    }

    /** {@code <quest>#<index>:<label>} keys already warned about, so each is logged only once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private MissingContent() {
    }

    /** The configured policy; anything unrecognised falls back to {@link Policy#SKIP}. */
    public static Policy policy() {
        String configured = NeroQuestsConfig.MISSING_MOD_OBJECTIVE_POLICY.get();
        if (configured != null && "autocomplete".equals(configured.trim().toLowerCase(Locale.ROOT))) {
            return Policy.AUTOCOMPLETE;
        }
        return Policy.SKIP;
    }

    /**
     * Warn about one unresolvable objective, at most once per server run.
     *
     * @param quest          the owning quest id
     * @param objectiveIndex the objective's position in that quest
     * @param objective      the objective itself (only its type id and content label are logged)
     * @param policy         the policy being applied, named in the message
     */
    public static void warnOnce(Identifier quest, int objectiveIndex, ObjectiveSpec objective, Policy policy) {
        String key = quest + "#" + objectiveIndex + ":" + objective.contentLabel();
        if (!WARNED.add(key)) {
            return;
        }
        NeroQuestsCommon.LOGGER.info(
                "[NeroQuests] Quest {} objective #{} ({}) references '{}', which is not present here; "
                        + "applying missingModObjectivePolicy={}.",
                quest, objectiveIndex, objective.typeId(), objective.contentLabel(),
                policy == Policy.AUTOCOMPLETE ? "autocomplete" : "skip");
    }

    /** Forget every warning, so a fresh world/datapack load reports its own gaps. */
    public static void reset() {
        WARNED.clear();
    }
}
