package at.magi.ygodiscordbot.impl.leaderboard;

import at.magi.ygodiscordbot.entity.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.entity.leaderboard.RankedPlayer;
import at.magi.ygodiscordbot.testutil.FakeInteractions;
import at.magi.ygodiscordbot.utils.discord.DisplayNames;
import org.testng.annotations.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static at.magi.ygodiscordbot.testutil.CommandAssert.assertGuildOnly;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertManageServerOnly;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertSubcommands;
import static at.magi.ygodiscordbot.testutil.FakeInteractions.guild;
import static at.magi.ygodiscordbot.testutil.FakeInteractions.returns;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
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

    @Test
    public void sharePostsEveryMessageWithoutPingsAfterTheDefer() {
        FakeInteractions fakes = new FakeInteractions();
        var channel = FakeInteractions.channel(Map.of("getAsMention", returns("<#5>"),
                "sendMessage", fakes.sends("post")));
        PlayerRepository onePlayer = new PlayerRepository(null) {
            @Override
            public LeaderboardPage page(int page) {
                return new LeaderboardPage(1, 1, 1, List.of(new RankedPlayer(1, 9, 100)));
            }
        };
        new LeaderboardAdminCommand(onePlayer, new DisplayNames(Clock.systemUTC()), Runnable::run)
                .execute(fakes.event(Map.of("getGuild", returns(guild()), "getChannel", returns(channel),
                        "getName", returns("leaderboard-admin"), "getSubcommandName", returns("share"))));
        List<String> sent = fakes.sent();
        assertEquals(sent.get(0), "reply (ephemeral)");
        List<String> posts = sent.subList(1, sent.size() - 1);
        assertFalse(posts.isEmpty(), sent.toString());
        assertTrue(posts.stream().allMatch(post -> post.startsWith("post:") && post.contains("Player 9")
                && post.endsWith(" mentions=[]")), sent.toString());
        assertEquals(sent.get(sent.size() - 1), "edit:✅ Posted the leaderboard in <#5>.");
    }
}
