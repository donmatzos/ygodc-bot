package at.magi.ygodiscordbot.command;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import org.testng.annotations.Test;

import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class LeaderboardAdminCommandTest {

    @Test
    public void allowedWhenBothCanTalk() {
        assertNull(LeaderboardAdminCommand.channelProblem(true, true, "#results"));
    }

    @Test
    public void memberMustBeAbleToPostThere() {
        String problem = LeaderboardAdminCommand.channelProblem(false, true, "#results");
        assertTrue(problem.contains("You can't send messages in #results"), problem);
    }

    @Test
    public void botMustBeAbleToPostThere() {
        String problem = LeaderboardAdminCommand.channelProblem(true, false, "#results");
        assertTrue(problem.contains("View Channel"), problem);
        assertTrue(problem.contains("Send Messages"), problem);
    }

    @Test
    public void restrictedToManageServerInServersByDefault() {
        var data = new LeaderboardAdminCommand(null, null, Runnable::run).data();
        assertEquals(data.getDefaultPermissions().getPermissionsRaw(),
                DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER).getPermissionsRaw());
        assertEquals(data.getContexts(), Set.of(InteractionContextType.GUILD));
        assertEquals(data.getSubcommands().get(0).getName(), "share");
    }
}
