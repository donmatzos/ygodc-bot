package at.magi.ygodiscordbot.impl.leaderboard;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import org.testng.annotations.Test;

import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class LeaderboardAdminCommandTest {

    @Test
    public void sharedLeaderboardNeverPings() {
        assertTrue(LeaderboardAdminCommand.SHARE_MENTIONS.isEmpty());
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
