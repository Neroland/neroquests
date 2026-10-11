package za.co.neroland.neroquests.custom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.QuestSource;

/**
 * One custom (editor-made) chapter as stored (D1). Its id is {@code custom:<slug>}; the slug is random
 * ({@link SlugGenerator}). The title is author-written plain text (sanitised, rendered literally). Holds no
 * player identity.
 *
 * @param slug      the random id path
 * @param title     sanitised author text, 1..64 code points
 * @param icon      an item id
 * @param order     sort key among custom chapters (ascending; ties by slug)
 * @param createdAt epoch millis
 */
public record CustomChapter(String slug, String title, Identifier icon, int order, long createdAt) {

    public static final Codec<CustomChapter> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("slug").forGetter(CustomChapter::slug),
            Codec.STRING.fieldOf("title").forGetter(CustomChapter::title),
            Identifier.CODEC.optionalFieldOf("icon", Chapter.DEFAULT_ICON).forGetter(CustomChapter::icon),
            Codec.INT.optionalFieldOf("order", 0).forGetter(CustomChapter::order),
            Codec.LONG.optionalFieldOf("created_at", 0L).forGetter(CustomChapter::createdAt)
    ).apply(inst, CustomChapter::of));

    private static CustomChapter of(String slug, String title, Identifier icon, Integer order, Long createdAt) {
        return new CustomChapter(slug, title, icon, order.intValue(), createdAt.longValue());
    }

    /** {@code custom:<slug>}. */
    public Identifier id() {
        return idOf(slug);
    }

    /** {@code custom:<slug>} for a chapter slug. */
    public static Identifier idOf(String slug) {
        return Identifier.fromNamespaceAndPath(QuestSource.CUSTOM_NAMESPACE, slug);
    }

    public CustomChapter withTitle(String newTitle, Identifier newIcon, int newOrder) {
        return new CustomChapter(slug, newTitle, newIcon, newOrder, createdAt);
    }
}
