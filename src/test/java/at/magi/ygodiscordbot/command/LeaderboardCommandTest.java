package at.magi.ygodiscordbot.command;

import at.magi.ygodiscordbot.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.leaderboard.RankedPlayer;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;

public class LeaderboardCommandTest {

    @Test
    public void emptyBoardReply() {
        assertEquals(LeaderboardCommand.noPageReply(new LeaderboardPage(1, 0, 0, List.of())),
                "No players on the leaderboard yet.");
        // Even a later page of an empty board says "empty", not "page does not exist"
        assertEquals(LeaderboardCommand.noPageReply(new LeaderboardPage(3, 0, 0, List.of())),
                "No players on the leaderboard yet.");
    }

    @Test
    public void missingPageReply() {
        assertEquals(LeaderboardCommand.noPageReply(new LeaderboardPage(4, 2, 30, List.of())),
                "Page 4 does not exist, the leaderboard has 2 pages.");
    }

    @Test
    public void existingPageHasNoErrorReply() {
        assertNull(LeaderboardCommand.noPageReply(new LeaderboardPage(1, 1, 1, List.of(new RankedPlayer(1, 5, 2)))));
    }

    @Test
    public void definitionHasOptionalBoundedPage() {
        var page = new LeaderboardCommand(null, null, Runnable::run).data().getOptions().get(0);
        assertEquals(page.getName(), "page");
        assertFalse(page.isRequired());
        assertEquals(page.getMinValue().longValue(), 1L);
        assertEquals(page.getMaxValue().longValue(), 10_000L);
    }
}
