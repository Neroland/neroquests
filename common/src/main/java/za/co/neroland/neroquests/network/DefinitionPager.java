package za.co.neroland.neroquests.network;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * Splits the quest-definition snapshot into size-bounded pages and puts it back together (AUD-21).
 * Pure Java — no Minecraft types — so the split/assemble logic is unit-tested on its own.
 *
 * <p><b>Server side:</b> {@link #split} packs entries in order into pages whose summed encoded size
 * stays within the budget. An entry that cannot fit even alone is handed to {@code oversized} and
 * skipped; it never makes encoding throw. There is always at least one page, so an empty definition
 * set still tells the client "nothing here".
 *
 * <p><b>Client side:</b> an {@link Assembler} collects the pages of one sync (identified by its sync
 * id) and releases the whole list only when every page has arrived, so the client mirror is swapped
 * atomically and never shows half a pack. A page of a newer sync discards the unfinished older one.
 */
public final class DefinitionPager {

    /** Upper bound on one definitions packet, well under the 1 MiB custom-payload ceiling. */
    public static final int MAX_PAGE_BYTES = 512 * 1024;

    /** Room kept for the page header (sync id, page index, page count, entry count — four VarInts). */
    public static final int HEADER_BYTES = 64;

    /**
     * Upper bound on pages in one sync, so a malformed packet cannot make the client allocate without limit.
     * 256 pages of 512 KiB is 128 MiB of definitions — far beyond what the content caps allow (a full
     * custom store at its maxima is a few MiB), so a real sync never comes near it.
     */
    public static final int MAX_PAGES = 256;

    private DefinitionPager() {
    }

    /**
     * Pack {@code items}, in order, into pages of at most {@code budget} encoded bytes each.
     *
     * @param size      the encoded size of one item, in bytes
     * @param budget    the byte budget of one page's items
     * @param oversized receives every item larger than {@code budget} on its own (it is skipped)
     * @return at least one page; never more than {@link #MAX_PAGES} (excess items go to {@code oversized})
     */
    public static <T> List<List<T>> split(List<T> items, ToIntFunction<T> size, int budget, Consumer<T> oversized) {
        List<List<T>> pages = new ArrayList<>();
        List<T> current = new ArrayList<>();
        long used = 0;
        for (T item : items) {
            int itemSize = size.applyAsInt(item);
            if (itemSize < 0 || itemSize > budget) {
                oversized.accept(item);
                continue;
            }
            if (used + itemSize > budget && !current.isEmpty()) {
                if (pages.size() + 1 >= MAX_PAGES) {
                    oversized.accept(item);
                    continue;
                }
                pages.add(List.copyOf(current));
                current = new ArrayList<>();
                used = 0;
            }
            current.add(item);
            used += itemSize;
        }
        pages.add(List.copyOf(current));
        return List.copyOf(pages);
    }

    /** The bytes {@code FriendlyByteBuf.writeUtf} spends on {@code text}: VarInt length prefix + UTF-8. */
    public static int utfSize(String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return varIntSize(bytes) + bytes;
    }

    /** The bytes a VarInt of {@code value} takes on the wire. */
    public static int varIntSize(int value) {
        int size = 1;
        int rest = value >>> 7;
        while (rest != 0) {
            size++;
            rest >>>= 7;
        }
        return size;
    }

    /**
     * Collects the pages of one sync. Not thread-safe by itself; the client calls it from its network
     * thread hand-off only (and {@link #clear()} on disconnect), both synchronised here.
     */
    public static final class Assembler<T> {

        private int syncId;
        private List<List<T>> pages;
        private int received;

        /**
         * Offer one page.
         *
         * @return the complete entry list (all pages, in page order) once the last page of this sync has
         *         arrived; empty while pages are missing or when the page is malformed (ignored)
         */
        public synchronized Optional<List<T>> accept(int sync, int index, int total, List<T> items) {
            if (total < 1 || total > MAX_PAGES || index < 0 || index >= total) {
                return Optional.empty();
            }
            if (pages == null || sync != syncId || pages.size() != total) {
                syncId = sync;
                pages = new ArrayList<>(total);
                for (int i = 0; i < total; i++) {
                    pages.add(null);
                }
                received = 0;
            }
            if (pages.get(index) == null) {
                pages.set(index, List.copyOf(items));
                received++;
            }
            if (received < total) {
                return Optional.empty();
            }
            List<T> all = new ArrayList<>();
            for (List<T> page : pages) {
                all.addAll(page);
            }
            pages = null;
            received = 0;
            return Optional.of(List.copyOf(all));
        }

        /** Forget any half-received sync (on disconnect). */
        public synchronized void clear() {
            pages = null;
            received = 0;
        }
    }
}
