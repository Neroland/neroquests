package za.co.neroland.neroquests.custom;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;

/**
 * The dependency graph the editor checks before it links two quests (D2): an edge from a quest to every
 * prerequisite and every {@code quest_complete} target. Pure.
 */
public final class CustomGraph {

    private CustomGraph() {
    }

    /** Every quest's dependencies (prerequisites plus {@code quest_complete} targets). */
    public static Map<Identifier, Set<Identifier>> dependencies(Collection<Quest> quests) {
        Map<Identifier, Set<Identifier>> out = new LinkedHashMap<>();
        for (Quest quest : quests) {
            out.put(quest.id(), dependenciesOf(quest));
        }
        return out;
    }

    /** One quest's dependencies. */
    public static Set<Identifier> dependenciesOf(Quest quest) {
        Set<Identifier> deps = new LinkedHashSet<>(quest.prerequisites());
        for (ObjectiveSpec objective : quest.objectives()) {
            if (objective instanceof QuestCompleteObjective complete) {
                deps.add(complete.quest());
            }
        }
        return deps;
    }

    /**
     * Whether giving {@code subject} the dependencies {@code newDeps} would close a cycle: some new dependency
     * already depends, directly or transitively, on {@code subject} (or is {@code subject}).
     */
    public static boolean createsCycle(Map<Identifier, ? extends Collection<Identifier>> graph, Identifier subject,
                                       Collection<Identifier> newDeps) {
        Deque<Identifier> pending = new ArrayDeque<>(newDeps);
        Set<Identifier> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            Identifier current = pending.poll();
            if (current.equals(subject)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            Collection<Identifier> next = graph.get(current);
            if (next != null) {
                pending.addAll(next);
            }
        }
        return false;
    }
}
