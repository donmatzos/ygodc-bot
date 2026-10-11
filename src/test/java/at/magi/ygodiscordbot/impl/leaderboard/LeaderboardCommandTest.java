package at.magi.ygodiscordbot.impl.leaderboard;

import at.magi.ygodiscordbot.entity.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.entity.leaderboard.RankedPlayer;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static at.magi.ygodiscordbot.testutil.CommandAssert.assertBotCheckedManageServer;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertOpenToEveryone;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertSubcommands;
import static at.magi.ygodiscordbot.testutil.CommandAssert.option;
import static at.magi.ygodiscordbot.testutil.CommandAssert.sub;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class LeaderboardCommandTest {

    private static final SlashCommandData DATA = new LeaderboardCommand(null, null, Runnable::run).data();

    @DataProvider
    public Object[][] pages() {
        return new Object[][]{
                {"empty board", new LeaderboardPage(1, 0, 0, List.of()), "No players on the leaderboard yet."},
                // Even a later page of an empty board says "empty", not "page does not exist"
                {"later page of an empty board", new LeaderboardPage(3, 0, 0, List.of()),
                        "No players on the leaderboard yet."},
                {"missing page", new LeaderboardPage(4, 2, 30, List.of()),
                        "Page 4 does not exist, the leaderboard has 2 pages."},
                {"existing page", new LeaderboardPage(1, 1, 1, List.of(new RankedPlayer(1, 5, 2))), null},
        };
    }

    @Test(dataProvider = "pages")
    public void noPageReply(String name, LeaderboardPage page, String expected) {
        assertEquals(LeaderboardCommand.noPageReply(page), expected, name);
    }

    @Test
    public void pageSubcommandKeepsBoundedOption() {
        var page = option(sub(DATA, "page"), "page");
        assertFalse(page.isRequired());
        assertEquals(page.getMinValue().longValue(), 1L);
        assertEquals(page.getMaxValue().longValue(), 10_000L);
    }

    @Test
    public void subcommands() {
        assertSubcommands(DATA, "page", "get", "add", "update");
        assertOpenToEveryone(DATA);
        assertFalse(option(sub(DATA, "get"), "player").isRequired());
        assertTrue(option(sub(DATA, "add"), "player").isRequired());
        assertTrue(sub(DATA, "add").getDescription().endsWith("(Manage Server)"));
        assertTrue(sub(DATA, "update").getDescription().endsWith("(Manage Server)"));
    }

    @Test
    public void updatePointsRange() {
        var points = option(sub(DATA, "update"), "points");
        assertTrue(points.isRequired());
        assertEquals(points.getMinValue().longValue(), 0L);
        assertEquals(points.getMaxValue().longValue(), 999_999L);
        assertEquals(points.getDescription(), "New total (0–999,999)");
    }

    @DataProvider
    public Object[][] changes() {
        return new Object[][]{
                {"outside a server", false, false, false, "❌ The leaderboard can only be changed in a server."},
                {"without Manage Server", true, false, false,
                        "❌ You need **Manage Server** to change the leaderboard."},
                {"target is a bot", true, true, true, "❌ Bots can't be on the leaderboard."},
                {"allowed", true, true, false, null},
        };
    }

    @Test(dataProvider = "changes")
    public void changeProblem(String name, boolean inGuild, boolean canManageServer, boolean targetIsBot,
                              String expected) {
        assertEquals(LeaderboardCommand.changeProblem(inGuild, canManageServer, targetIsBot), expected, name);
    }

    @Test
    public void permissionAndTargetChecksAreSeparate() {
        assertNull(LeaderboardCommand.permissionProblem(true, true));
        assertEquals(LeaderboardCommand.permissionProblem(true, false),
                "❌ You need **Manage Server** to change the leaderboard.");
        assertEquals(LeaderboardCommand.permissionProblem(false, true),
                "❌ The leaderboard can only be changed in a server.");
        // Permission wins over the target when both are wrong
        assertEquals(LeaderboardCommand.changeProblem(true, false, true),
                LeaderboardCommand.permissionProblem(true, false));
        assertNull(LeaderboardCommand.targetProblem(false));
        assertEquals(LeaderboardCommand.targetProblem(true), "❌ Bots can't be on the leaderboard.");
    }

    @Test
    public void botCheckedManageServerSubcommands() {
        assertBotCheckedManageServer(new LeaderboardCommand(null, null, Runnable::run), "add", "update");
    }
}
