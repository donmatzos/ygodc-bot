package at.magi.ygodiscordbot.impl.leaderboard;

import org.testng.annotations.Test;

import java.util.List;

import static at.magi.ygodiscordbot.testutil.CommandAssert.assertGuildOnly;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertManageServerOnly;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertSubcommands;
import static at.magi.ygodiscordbot.testutil.CommandAssert.option;
import static at.magi.ygodiscordbot.testutil.CommandAssert.sub;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PointsCommandTest {

    @Test
    public void adminOnlyInServers() {
        var data = new PointsCommand(null, Runnable::run).data();
        assertEquals(data.getName(), "points");
        assertManageServerOnly(data);
        assertGuildOnly(data);
        assertSubcommands(data, "add", "remove");
    }

    @Test
    public void amountIsRequiredAndLimitedTo99() {
        var data = new PointsCommand(null, Runnable::run).data();
        for (String name : List.of("add", "remove")) {
            var sub = sub(data, name);
            var player = option(sub, "player");
            var amount = option(sub, "amount");
            assertTrue(player.isRequired());
            assertTrue(amount.isRequired());
            assertEquals(amount.getMinValue().longValue(), 1L);
            assertEquals(amount.getMaxValue().longValue(), 99L);
        }
    }

    @Test
    public void removeIsNegative() {
        assertEquals(PointsCommand.delta("add", 5), 5);
        assertEquals(PointsCommand.delta("remove", 5), -5);
    }
}
