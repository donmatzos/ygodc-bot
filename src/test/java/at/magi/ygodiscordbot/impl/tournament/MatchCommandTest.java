package at.magi.ygodiscordbot.impl.tournament;

import org.testng.annotations.Test;

import static at.magi.ygodiscordbot.testutil.CommandAssert.assertGuildOnly;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertManageServerOnly;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertOpenToEveryone;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertSubcommands;
import static at.magi.ygodiscordbot.testutil.CommandAssert.option;
import static at.magi.ygodiscordbot.testutil.CommandAssert.sub;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class MatchCommandTest {

    @Test
    public void playersCanReportInServers() {
        var data = MatchCommand.forPlayers(null, Runnable::run).data();
        assertEquals(data.getName(), "match");
        assertOpenToEveryone(data);
        assertGuildOnly(data);
        assertSubcommands(data, "finish", "doubleloss");
        var finish = sub(data, "finish");
        var id = option(finish, "id");
        assertTrue(id.isRequired());
        assertEquals(id.getMinValue().longValue(), 10_000L);
        assertEquals(id.getMaxValue().longValue(), 99_999L);
        assertTrue(option(finish, "winner").isRequired());
        var doubleLoss = sub(data, "doubleloss");
        assertEquals(doubleLoss.getOptions().size(), 1);
        option(doubleLoss, "id");
    }

    @Test
    public void adminVersionNeedsManageServer() {
        var data = MatchCommand.forAdmins(null, Runnable::run).data();
        assertEquals(data.getName(), "match-admin");
        assertManageServerOnly(data);
        assertGuildOnly(data);
    }
}
