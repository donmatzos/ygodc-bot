package at.magi.ygodiscordbot.impl.leaderboard;

import org.testng.annotations.Test;

import static at.magi.ygodiscordbot.testutil.CommandAssert.assertGuildOnly;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertManageServerOnly;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertSubcommands;
import static org.testng.Assert.assertTrue;

public class LeaderboardAdminCommandTest {

    @Test
    public void sharedLeaderboardNeverPings() {
        assertTrue(LeaderboardAdminCommand.SHARE_MENTIONS.isEmpty());
    }

    @Test
    public void restrictedToManageServerInServersByDefault() {
        var data = new LeaderboardAdminCommand(null, null, Runnable::run).data();
        assertManageServerOnly(data);
        assertGuildOnly(data);
        assertSubcommands(data, "share");
    }
}
