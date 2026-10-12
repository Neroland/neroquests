package za.co.neroland.neroquests.client.screen;

import java.util.Map;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import za.co.neroland.nerolandcore.progression.ClientGates;

import za.co.neroland.neroquests.client.ClientQuestDefinitions;
import za.co.neroland.neroquests.client.ClientQuestProgress;
import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestStates;

/**
 * One chapter's graph, ready to draw: the visible nodes with their world positions and render states, the routed
 * dependency lines, label room and the content bounds. Built once per chapter / sync / progress change (and on a
 * slow timer for gate changes) by {@link QuestBookScreen}, so the per-frame draw only maps world pixels to the
 * screen and never allocates in its loops.
 *
 * <p>Only visible quests are part of it ({@link QuestBookScreen#visibleQuest}); a hidden prerequisite produces
 * no line, exactly as the book always behaved.
 */
final class ChapterGraph {

    final Chapter chapter;
    final int count;
    final Quest[] quests;
    final int[] worldX;
    final int[] worldY;
    final int[] labelRoom;
    final QuestBookScreen.NodeState[] states;
    /** Objective completion 0..1 (sum of counters over sum of targets). */
    final float[] progress;
    /** Number of completed objectives / number of objectives. */
    final int[] objectivesDone;
    final int[] edgeFrom;
    final int[] edgeTo;
    final int[][] routes;
    /** Per edge: the prerequisite is met (by its own scope). */
    final boolean[] edgeMet;
    /** World bounds of nodes plus labels; meaningless when {@code count == 0}. */
    final int minX;
    final int minY;
    final int maxX;
    final int maxY;
    /** Visible and completed quests of the chapter. */
    final int completed;

    /** Scratch arrays for the per-frame screen positions (same length as the nodes). */
    final int[] screenX;
    final int[] screenY;
    final int[][] screenRoutes;
    /** Trimmed label per node and the pixel room it was trimmed for (-1 = not yet), so labels are not re-cut every frame. */
    final Component[] labels;
    final int[] labelTrimmedFor;

    private ChapterGraph(Chapter chapter, int count, Quest[] quests, int[] worldX, int[] worldY,
                         int edges, int[] edgeFrom, int[] edgeTo, int[][] routes, boolean[] edgeMet) {
        this.chapter = chapter;
        this.count = count;
        this.quests = quests;
        this.worldX = worldX;
        this.worldY = worldY;
        this.edgeFrom = edgeFrom;
        this.edgeTo = edgeTo;
        this.routes = routes;
        this.edgeMet = edgeMet;
        this.labelRoom = new int[count];
        this.states = new QuestBookScreen.NodeState[count];
        this.progress = new float[count];
        this.objectivesDone = new int[count];
        this.screenX = new int[count];
        this.screenY = new int[count];
        this.screenRoutes = new int[edges][];
        this.labels = new Component[count];
        this.labelTrimmedFor = new int[count];
        java.util.Arrays.fill(this.labelTrimmedFor, -1);
        for (int edge = 0; edge < edges; edge++) {
            this.screenRoutes[edge] = new int[routes[edge].length];
        }
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int index = 0; index < count; index++) {
            this.labelRoom[index] = GraphGeometry.labelRoom(index, worldX, worldY, count);
            int half = Math.max(GraphGeometry.NODE, this.labelRoom[index]) / 2;
            int centre = worldX[index] + GraphGeometry.NODE / 2;
            left = Math.min(left, centre - half);
            right = Math.max(right, centre + half);
            top = Math.min(top, worldY[index]);
            bottom = Math.max(bottom, worldY[index] + GraphGeometry.NODE + GraphGeometry.LABEL_HEIGHT);
        }
        this.minX = count == 0 ? 0 : left;
        this.minY = count == 0 ? 0 : top;
        this.maxX = count == 0 ? 0 : right;
        this.maxY = count == 0 ? 0 : bottom;

        Map<Identifier, Quest> definitions = ClientQuestDefinitions.quests();
        Set<Identifier> own = ClientQuestProgress.ownCompletedQuests();
        Set<Identifier> server = ClientQuestProgress.serverCompletedQuests();
        int done = 0;
        for (int index = 0; index < count; index++) {
            Quest quest = quests[index];
            QuestProgress row = ClientQuestProgress.progress(quest);
            this.states[index] = switch (QuestStates.stateOf(quest, definitions, own, server, row, ClientGates::isOpen)) {
                case COMPLETED -> QuestBookScreen.NodeState.COMPLETED;
                case LOCKED -> QuestBookScreen.NodeState.LOCKED;
                case IN_PROGRESS -> QuestBookScreen.NodeState.IN_PROGRESS;
                case AVAILABLE -> QuestBookScreen.NodeState.AVAILABLE;
            };
            if (this.states[index] == QuestBookScreen.NodeState.COMPLETED) {
                done++;
            }
            long sum = 0;
            long total = 0;
            int finished = 0;
            int objectiveIndex = 0;
            for (ObjectiveSpec spec : quest.objectives()) {
                int target = Math.max(1, spec.target());
                int current = Mth.clamp(row.counter(objectiveIndex), 0, target);
                sum += current;
                total += target;
                if (current >= target) {
                    finished++;
                }
                objectiveIndex++;
            }
            this.progress[index] = row.isComplete() ? 1.0F : total == 0 ? 0.0F : (float) sum / (float) total;
            this.objectivesDone[index] = row.isComplete() ? quest.objectives().size() : finished;
        }
        this.completed = done;
        for (int edge = 0; edge < edges; edge++) {
            this.edgeMet[edge] = QuestStates.prerequisiteMet(quests[edgeFrom[edge]].id(), definitions, own, server);
        }
    }

    /**
     * Builds the graph of {@code chapter}: visible entries only, honouring node moves that are still waiting for
     * the server's confirmation.
     */
    static ChapterGraph build(Chapter chapter, Map<Identifier, int[]> pendingMoves) {
        int size = chapter.quests().size();
        Quest[] quests = new Quest[size];
        int[] xs = new int[size];
        int[] ys = new int[size];
        int count = 0;
        for (Chapter.Entry entry : chapter.quests()) {
            Quest quest = QuestBookScreen.visibleQuest(entry.quest());
            if (quest == null) {
                continue;
            }
            int[] pending = pendingMoves.get(entry.quest());
            quests[count] = quest;
            xs[count] = GraphGeometry.world(pending == null ? entry.x() : pending[0]);
            ys[count] = GraphGeometry.world(pending == null ? entry.y() : pending[1]);
            count++;
        }
        int edges = 0;
        for (int index = 0; index < count; index++) {
            for (Identifier prerequisite : quests[index].prerequisites()) {
                if (indexOf(quests, count, prerequisite) >= 0) {
                    edges++;
                }
            }
        }
        int[] from = new int[edges];
        int[] to = new int[edges];
        int[][] routes = new int[edges][];
        int edge = 0;
        for (int index = 0; index < count; index++) {
            for (Identifier prerequisite : quests[index].prerequisites()) {
                int source = indexOf(quests, count, prerequisite);
                if (source < 0) {
                    continue;
                }
                from[edge] = source;
                to[edge] = index;
                routes[edge] = GraphRouting.route(xs[source], ys[source], xs[index], ys[index], GraphGeometry.NODE);
                edge++;
            }
        }
        return new ChapterGraph(chapter, count, quests, xs, ys, edges, from, to, routes, new boolean[edges]);
    }

    private static int indexOf(Quest[] quests, int count, Identifier id) {
        for (int index = 0; index < count; index++) {
            if (quests[index].id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /** Index of the node for {@code id}, or -1 when it is not drawn here. */
    int indexOf(Identifier id) {
        return id == null ? -1 : indexOf(this.quests, this.count, id);
    }

    int edgeCount() {
        return this.edgeFrom.length;
    }
}
