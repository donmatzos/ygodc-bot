package at.magi.ygodiscordbot.impl.tournament;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class MatchCommandTest {

    @Test
    public void playersCanReportInServers() {
        var data = MatchCommand.forPlayers(null, Runnable::run).data();
        assertEquals(data.getName(), "match");
        assertEquals(data.getDefaultPermissions(), DefaultMemberPermissions.ENABLED);
        assertEquals(data.getContexts(), Set.of(InteractionContextType.GUILD));
        var finish = data.getSubcommands().get(0);
        assertEquals(finish.getName(), "finish");
        var id = finish.getOptions().get(0);
        assertEquals(id.getName(), "id");
        assertTrue(id.isRequired());
        assertEquals(id.getMinValue().longValue(), 10_000L);
        assertEquals(id.getMaxValue().longValue(), 99_999L);
        assertEquals(finish.getOptions().get(1).getName(), "winner");
        assertEquals(data.getSubcommands().stream().map(SubcommandData::getName).toList(),
                List.of("finish", "doubleloss"));
        var doubleLoss = data.getSubcommands().get(1);
        assertEquals(doubleLoss.getOptions().size(), 1);
        assertEquals(doubleLoss.getOptions().get(0).getName(), "id");
    }

    @Test
    public void adminVersionNeedsManageServer() {
        var data = MatchCommand.forAdmins(null, Runnable::run).data();
        assertEquals(data.getName(), "match-admin");
        assertEquals(data.getDefaultPermissions().getPermissionsRaw().longValue(), Permission.MANAGE_SERVER.getRawValue());
        assertEquals(data.getContexts(), Set.of(InteractionContextType.GUILD));
    }
}
