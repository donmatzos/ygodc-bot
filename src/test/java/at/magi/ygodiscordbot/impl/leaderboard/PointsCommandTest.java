package at.magi.ygodiscordbot.impl.leaderboard;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PointsCommandTest {

    @Test
    public void adminOnlyInServers() {
        var data = new PointsCommand(null, Runnable::run).data();
        assertEquals(data.getName(), "points");
        assertEquals(data.getDefaultPermissions().getPermissionsRaw().longValue(), Permission.MANAGE_SERVER.getRawValue());
        assertEquals(data.getContexts(), Set.of(InteractionContextType.GUILD));
        assertEquals(data.getSubcommands().stream().map(SubcommandData::getName).toList(), List.of("add", "remove"));
    }

    @Test
    public void amountIsRequiredAndLimitedTo99() {
        for (SubcommandData sub : new PointsCommand(null, Runnable::run).data().getSubcommands()) {
            var player = sub.getOptions().get(0);
            var amount = sub.getOptions().get(1);
            assertEquals(player.getName(), "player");
            assertTrue(player.isRequired());
            assertEquals(amount.getName(), "amount");
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
