package at.magi.ygodiscordbot.utils.discord;

import at.magi.ygodiscordbot.utils.discord.DcMessageUtils.Section;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.List;

import static at.magi.ygodiscordbot.testutil.DiscordAssert.assertSendable;
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
        assertSendable(messages);
        for (String message : messages) {
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
        assertSendable(messages);
        for (String message : messages) {
            assertEquals(message.split("```", -1).length - 1, 2, "expected exactly one code block");
        }
    }

    @Test
    public void safeReplacesBackticks() {
        assertEquals(DcMessageUtils.safe("a`b"), "a'b");
    }

    @Test(timeOut = 5000)
    public void rowThatNeverFitsIsTruncated() {
        List<String> messages = DcMessageUtils.packTables("## Intro", List.of(
                new Section("Heading", "Heading (continued)", "COLS", List.of("a", "x".repeat(1990), "b"))));
        assertSendable(messages);
        for (String message : messages) {
            assertEquals(message.split("```", -1).length - 1, 2, "expected exactly one code block");
        }
        String all = String.join("\n", messages);
        assertTrue(all.contains("x".repeat(100) + "…\n```"), "row not truncated with an ellipsis");
        assertTrue(all.lines().anyMatch(line -> line.equals("a")) && all.lines().anyMatch(line -> line.equals("b")));
    }

    @Test(timeOut = 5000)
    public void rowUnderAnAbsurdHeadingIsSkippedInsteadOfLooping() {
        // The first heading alone exceeds a message, so "a" can never be added; it is skipped and "b" goes under the
        // short continued heading
        List<String> messages = DcMessageUtils.packTables("## Intro", List.of(
                new Section("H".repeat(2100), "continued", "COLS", List.of("a", "b"))));
        String all = String.join("\n", messages);
        assertTrue(messages.get(0).startsWith("## Intro"), messages.get(0));
        assertFalse(all.lines().anyMatch(line -> line.equals("a")), "row under the absurd heading was kept");
        assertEquals(messages.get(messages.size() - 1), "continued\n```\nCOLS\nb\n```");
        // The message with the absurd heading (index 1) is oversized on purpose; the intro and the last must be fine
        assertSendable(List.of(messages.get(0), messages.get(messages.size() - 1)));
    }

    @Test
    public void boldEscapesMarkdownInsideTheName() {
        assertEquals(DcMessageUtils.bold("Dragons"), "**Dragons**");
        assertEquals(DcMessageUtils.bold("**x||y||"), "****x\\||y\\||**");
    }

    @Test
    public void escapeNeutralisesMarkdown() {
        assertEquals(DcMessageUtils.escape("Dragons"), "Dragons");
        assertEquals(DcMessageUtils.escape("**x||y||"), "**x\\||y\\||");
    }
}
