package at.magi.ygodiscordbot.impl.tournament;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TournamentCommandTest {

    private static final long FIRST = 100_000_000_000_000_001L;
    private static final long SECOND = 100_000_000_000_000_002L;

    private static String mentions(int count) {
        return LongStream.range(0, count).mapToObj(i -> "<@" + (FIRST + i) + ">").collect(Collectors.joining(" "));
    }

    @Test
    public void adminOnlyInServers() {
        var data = new TournamentCommand(null, Runnable::run).data();
        assertEquals(data.getName(), "tournament");
        assertEquals(data.getDefaultPermissions().getPermissionsRaw().longValue(), Permission.MANAGE_SERVER.getRawValue());
        assertEquals(data.getContexts(), Set.of(InteractionContextType.GUILD));
        assertEquals(data.getSubcommands().stream().map(SubcommandData::getName).toList(),
                List.of("start", "continue", "standings", "cancel", "drop"));
    }

    @Test
    public void readsMentionsInOrder() {
        TournamentCommand.PlayerList list = TournamentCommand.parsePlayers("<@" + SECOND + "> <@!" + FIRST + ">");
        assertNull(list.problem());
        assertEquals(list.ids(), List.of(SECOND, FIRST));
    }

    @Test
    public void commasAndLineBreaksAreFine() {
        TournamentCommand.PlayerList list = TournamentCommand.parsePlayers("<@" + FIRST + ">,\n<@" + SECOND + ">, ");
        assertEquals(list.ids(), List.of(FIRST, SECOND));
    }

    @Test
    public void duplicatesAreRefused() {
        String problem = TournamentCommand.parsePlayers("<@" + FIRST + "> <@" + FIRST + ">").problem();
        assertTrue(problem.contains("twice"), problem);
    }

    @Test
    public void plainTextNamesAreRefused() {
        String problem = TournamentCommand.parsePlayers("<@" + FIRST + "> @yugi").problem();
        assertTrue(problem.contains("@mentions"), problem);
        assertTrue(problem.contains("@yugi"), problem);
    }

    @Test
    public void roleMentionsAreRefused() {
        String problem = TournamentCommand.parsePlayers(mentions(2) + " <@&" + SECOND + ">").problem();
        assertTrue(problem.contains("@mentions"), problem);
    }

    @Test
    public void playerCountIsLimited() {
        assertTrue(TournamentCommand.parsePlayers(mentions(1)).problem().contains("2–32"));
        assertTrue(TournamentCommand.parsePlayers(mentions(33)).problem().contains("2–32"));
        assertNull(TournamentCommand.parsePlayers(mentions(32)).problem());
    }
}
