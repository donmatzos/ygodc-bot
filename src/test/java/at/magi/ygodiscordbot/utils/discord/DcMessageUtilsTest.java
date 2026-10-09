package at.magi.ygodiscordbot.utils.discord;

import at.magi.ygodiscordbot.utils.discord.DcMessageUtils.Section;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class DcMessageUtilsTest {

    @Test
    public void shortLinesStayInOneMessage() {
        assertEquals(DcMessageUtils.packLines("Your decks (2):", List.of("• A", "• B")),
                List.of("Your decks (2):\n• A\n• B"));
    }

    @Test
    public void longLinesAreSplitAtLineBoundaries() {
        List<String> lines = Collections.nCopies(100, "x".repeat(49));
        List<String> messages = DcMessageUtils.packLines("Header", lines);
        assertTrue(messages.size() > 1);
        assertTrue(messages.get(0).startsWith("Header\n"));
        for (String message : messages) {
            assertTrue(message.length() <= DcMessageUtils.MAX_MESSAGE_LENGTH, "too long: " + message.length());
            assertFalse(message.startsWith("\n"), "starts with a blank line");
        }
        assertEquals(String.join("\n", messages).lines().filter(line -> line.equals("x".repeat(49))).count(), 100L);
    }

    @Test
    public void continuedTableRepeatsHeaderAndHeading() {
        List<String> rows = Collections.nCopies(100, "r".repeat(40));
        List<String> messages = DcMessageUtils.packTables("## Intro",
                List.of(new Section("Heading", "Heading (continued)", "COLS", rows)));
        assertTrue(messages.size() > 1);
        assertTrue(messages.get(1).startsWith("Heading (continued)\n```\nCOLS\n"), messages.get(1));
        for (String message : messages) {
            assertTrue(message.length() <= DcMessageUtils.MAX_MESSAGE_LENGTH, "too long: " + message.length());
            assertEquals(message.split("```", -1).length - 1, 2, "unbalanced code block");
        }
    }

    @Test
    public void safeReplacesBackticks() {
        assertEquals(DcMessageUtils.safe("a`b"), "a'b");
    }
}
