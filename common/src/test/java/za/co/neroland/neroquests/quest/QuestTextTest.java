package za.co.neroland.neroquests.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

/**
 * J1 (§13 text): author text is rendered inert. A custom title that looks like a translation key, a JSON
 * component or a formatting code is shown verbatim as a literal; only spine/pack text is a translation key.
 */
class QuestTextTest {

    private static Quest quest(String id, String title) {
        return new Quest(Identifier.parse(id), title, "", Quest.DEFAULT_ICON, List.of(), List.of(), List.of(),
                QuestScope.PLAYER, Optional.empty());
    }

    @Test
    void customTextIsALiteralNeverAKey() {
        String hostile = "quest.neroquests.groundwork.title {\"text\":\"x\",\"clickEvent\":{}}";
        Component title = QuestText.title(quest("custom:abcd2345/efgh6723", hostile));
        PlainTextContents contents = assertInstanceOf(PlainTextContents.class, title.getContents());
        assertEquals(hostile, contents.text());
    }

    @Test
    void spineAndPackTextIsTranslated() {
        Component spine = QuestText.title(quest("neroquests:groundwork/what_this_book_is",
                "quest.neroquests.groundwork.what_this_book_is.title"));
        assertEquals("quest.neroquests.groundwork.what_this_book_is.title",
                assertInstanceOf(TranslatableContents.class, spine.getContents()).getKey());
    }
}
