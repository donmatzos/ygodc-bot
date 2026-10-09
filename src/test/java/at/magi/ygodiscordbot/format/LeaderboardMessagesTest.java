package at.magi.ygodiscordbot.format;

import at.magi.ygodiscordbot.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.leaderboard.RankedPlayer;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class LeaderboardMessagesTest {

    @Test
    public void rendersRankPlayerPointsTable() {
        LeaderboardPage page = new LeaderboardPage(1, 3, 47, List.of(
                new RankedPlayer(1, 10, 120), new RankedPlayer(2, 11, 98), new RankedPlayer(2, 12, 98)));
        List<String> messages = LeaderboardMessages.page("🏆 Leaderboard", page,
                Map.of(10L, "Yugi", 11L, "Kaiba", 12L, "Joey"));
        assertEquals(messages, List.of("""
                ## 🏆 Leaderboard

                Page 1 of 3 · 47 players
                ```
                Rank  Player  Points
                ----  ------  ------
                   1  Yugi       120
                   2  Kaiba       98
                   2  Joey        98
                ```"""));
    }

    @Test
    public void playerColumnGrowsWithLongestName() {
        LeaderboardPage page = new LeaderboardPage(1, 1, 2, List.of(new RankedPlayer(1, 10, 5), new RankedPlayer(2, 11, 3)));
        String message = LeaderboardMessages.page("T", page, Map.of(10L, "Seto Kaiba", 11L, "Yugi")).get(0);
        assertTrue(message.contains("Rank  Player      Points\n----  ----------  ------\n"), message);
        assertTrue(message.contains("   1  Seto Kaiba       5\n   2  Yugi             3\n"), message);
    }

    @Test
    public void singlePlayerIsSingular() {
        LeaderboardPage page = new LeaderboardPage(1, 1, 1, List.of(new RankedPlayer(1, 10, 3)));
        assertTrue(LeaderboardMessages.page("T", page, Map.of(10L, "Yugi")).get(0).contains("Page 1 of 1 · 1 player\n"));
    }

    @Test
    public void unknownUserFallback() {
        LeaderboardPage page = new LeaderboardPage(1, 1, 2, List.of(new RankedPlayer(1, 10, 5), new RankedPlayer(2, 99, 1)));
        String message = LeaderboardMessages.page("T", page, Map.of(10L, "Yugi")).get(0);
        assertTrue(message.contains("Yugi"), message);
        assertTrue(message.contains("Unknown user (99)"), message);
    }

    @Test
    public void backticksCannotCloseBlock() {
        LeaderboardPage page = new LeaderboardPage(1, 1, 1, List.of(new RankedPlayer(1, 10, 5)));
        String message = LeaderboardMessages.page("T", page, Map.of(10L, "```evil")).get(0);
        assertEquals(message.split("```", -1).length - 1, 2, message);
    }

    @Test
    public void fullPageFitsOneMessage() {
        List<String> messages = LeaderboardMessages.page("🏆 Leaderboard", widePage(LeaderboardPage.PAGE_SIZE),
                widestNames(LeaderboardPage.PAGE_SIZE));
        assertEquals(messages.size(), 1);
        assertTrue(messages.get(0).length() <= DcMessageUtils.MAX_MESSAGE_LENGTH, "length " + messages.get(0).length());
    }

    @Test
    public void oversizedTableIsSplitWithHeaderRepeated() {
        List<String> messages = LeaderboardMessages.page("🏆 Leaderboard", widePage(60), widestNames(60));
        assertTrue(messages.size() > 1);
        for (String message : messages) {
            assertTrue(message.length() <= DcMessageUtils.MAX_MESSAGE_LENGTH, "length " + message.length());
            assertTrue(message.contains("```\nRank  Player"), "table header missing: " + message);
        }
        assertTrue(messages.get(1).startsWith("Page 500 of 600 · 12000 players (continued)"), messages.get(1));
    }

    @Test
    public void emptyBoard() {
        assertEquals(LeaderboardMessages.empty(), "No players on the leaderboard yet.");
    }

    @Test
    public void pageOutOfRange() {
        assertEquals(LeaderboardMessages.pageOutOfRange(new LeaderboardPage(9, 3, 47, List.of())),
                "Page 9 does not exist, the leaderboard has 3 pages.");
        assertEquals(LeaderboardMessages.pageOutOfRange(new LeaderboardPage(2, 1, 4, List.of())),
                "Page 2 does not exist, the leaderboard has 1 page.");
    }

    /** Rows with large ranks and points (the record allows more rows than a real page, to force a split). */
    private static LeaderboardPage widePage(int rows) {
        List<RankedPlayer> players = new ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            players.add(new RankedPlayer(10_000 + i, i, 999_999_999L));
        }
        return new LeaderboardPage(500, 600, 12_000, players);
    }

    /** Discord display names are at most 32 characters. */
    private static Map<Long, String> widestNames(int rows) {
        Map<Long, String> names = new HashMap<>();
        for (long i = 1; i <= rows; i++) {
            names.put(i, "x".repeat(32));
        }
        return names;
    }
}
