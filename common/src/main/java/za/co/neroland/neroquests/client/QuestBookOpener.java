package za.co.neroland.neroquests.client;

/**
 * The one seam between the quest book <em>item</em> (common, loaded on dedicated servers) and the
 * quest book <em>screen</em> (client-only).
 *
 * <p>This class deliberately imports nothing from {@code net.minecraft.client}: it holds a plain
 * {@link Runnable} that each loader's <b>client</b> entry point installs during client bootstrap.
 * A dedicated server never installs one, so {@link #open()} is a no-op there and
 * {@code QuestBookScreen} is never class-loaded — the same structural client/server split Nerospace
 * uses, rather than a runtime {@code isClient()} check (which would still resolve the class).
 */
public final class QuestBookOpener {

    private static volatile Runnable opener;

    private QuestBookOpener() {
    }

    /** Installs the client-side screen opener. Called once from each loader's client entry point. */
    public static void setOpener(Runnable screenOpener) {
        opener = screenOpener;
    }

    /** Opens the quest book if a client opener has been installed; otherwise does nothing. */
    public static void open() {
        Runnable current = opener;
        if (current != null) {
            current.run();
        }
    }
}
