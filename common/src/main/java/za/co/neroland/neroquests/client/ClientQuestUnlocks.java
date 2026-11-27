package za.co.neroland.neroquests.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.network.QuestUnlockedPayload;

/**
 * The queue behind the top-left "Quest Unlocked" notification, fed by {@link QuestUnlockedPayload}.
 *
 * <p><b>Batching.</b> One payload is one batch (everything a single server sync unlocked). A batch of up to
 * {@value #SPLIT_LIMIT} quests is queued as one card per quest, shown one after another; a larger batch (first
 * join, a datapack reload, a gate that opens a whole chapter) becomes a single card naming the first quest
 * with "+N more". At most {@value #MAX_CARDS} cards wait at once: anything beyond that is folded into the
 * last waiting card's "+N more", so a burst can never keep the corner busy for long.
 *
 * <p>Pure data with no client-only imports, so it is safe to load on a dedicated server (the payload handler
 * is registered there as a type but never invoked). The notification drawer reads it on the render thread.
 */
public final class ClientQuestUnlocks {

    /** Batches up to this size get one card per quest. */
    static final int SPLIT_LIMIT = 3;

    /** Most cards waiting (including the one on screen). */
    static final int MAX_CARDS = 4;

    /**
     * One notification.
     *
     * @param quest the quest named on the card
     * @param more  how many other quests unlocked with it and are only counted
     */
    public record Card(Identifier quest, int more) {
    }

    private static final Deque<Card> QUEUE = new ArrayDeque<>();

    private ClientQuestUnlocks() {
    }

    /** Payload handler: queue one batch. */
    public static void accept(QuestUnlockedPayload payload) {
        enqueue(payload.quests(), payload.total());
    }

    /** Queue a batch of {@code total} unlocks, of which {@code quests} are named (pure queue logic). */
    public static synchronized void enqueue(List<Identifier> quests, int total) {
        if (quests.isEmpty()) {
            return;
        }
        if (total <= SPLIT_LIMIT && quests.size() == total) {
            for (Identifier quest : quests) {
                QUEUE.addLast(new Card(quest, 0));
            }
        } else {
            QUEUE.addLast(new Card(quests.get(0), Math.max(0, total - 1)));
        }
        while (QUEUE.size() > MAX_CARDS) {
            Card last = QUEUE.pollLast();
            Card previous = QUEUE.pollLast();
            QUEUE.addLast(new Card(previous.quest(), previous.more() + 1 + last.more()));
        }
    }

    /** The card to show now, or null. */
    public static synchronized Card peek() {
        return QUEUE.peekFirst();
    }

    /** The shown card is done (or cannot be shown). */
    public static synchronized void pop() {
        QUEUE.pollFirst();
    }

    /** How many cards are waiting. */
    public static synchronized int size() {
        return QUEUE.size();
    }

    /** Leaving a world: forget everything. */
    public static synchronized void clear() {
        QUEUE.clear();
    }
}
