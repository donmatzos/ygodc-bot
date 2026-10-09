package at.magi.ygodiscordbot.command;

import at.magi.ygodiscordbot.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.leaderboard.RankedPlayer;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

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

    private static SubcommandData sub(String name) {
        return new LeaderboardCommand(null, null, Runnable::run).data().getSubcommands().stream()
                .filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    public void pageSubcommandKeepsBoundedOption() {
        var page = sub("page").getOptions().get(0);
        assertEquals(page.getName(), "page");
        assertFalse(page.isRequired());
        assertEquals(page.getMinValue().longValue(), 1L);
        assertEquals(page.getMaxValue().longValue(), 10_000L);
    }

    @Test
    public void subcommands() {
        var data = new LeaderboardCommand(null, null, Runnable::run).data();
        assertEquals(data.getSubcommands().stream().map(SubcommandData::getName).toList(),
                List.of("page", "get", "add", "update"));
        assertNull(data.getDefaultPermissions().getPermissionsRaw());
        assertFalse(sub("get").getOptions().get(0).isRequired());
        assertTrue(sub("add").getOptions().get(0).isRequired());
        assertTrue(sub("add").getDescription().endsWith("(Manage Server)"));
        assertTrue(sub("update").getDescription().endsWith("(Manage Server)"));
    }

    @Test
    public void updatePointsRange() {
        var points = sub("update").getOptions().get(1);
        assertEquals(points.getName(), "points");
        assertTrue(points.isRequired());
        assertEquals(points.getMinValue().longValue(), 0L);
        assertEquals(points.getMaxValue().longValue(), 999_999L);
    }

    @Test
    public void changeProblemOutsideServer() {
        assertEquals(LeaderboardCommand.changeProblem(false, false, false),
                "❌ The leaderboard can only be changed in a server.");
    }

    @Test
    public void changeProblemWithoutManageServer() {
        assertEquals(LeaderboardCommand.changeProblem(true, false, false),
                "❌ You need **Manage Server** to change the leaderboard.");
    }

    @Test
    public void changeProblemForBots() {
        assertEquals(LeaderboardCommand.changeProblem(true, true, true), "❌ Bots can't be on the leaderboard.");
        assertNull(LeaderboardCommand.changeProblem(true, true, false));
    }
}
