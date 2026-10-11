package at.magi.ygodiscordbot.impl.help;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class HelpCommandTest {

    private static final long MEMBER = Permission.getRaw(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND);
    private static final long MANAGER = MEMBER | Permission.MANAGE_SERVER.getRawValue();
    private static final long ADMIN = Permission.ADMINISTRATOR.getRawValue();

    private static final Map<String, Set<String>> NONE = Map.of();
    private static final Map<String, Set<String>> CHECKED = Map.of("board", Set.of("add", "update"));

    private static SlashCommandData board() {
        return Commands.slash("board", "Board").addSubcommands(new SubcommandData("get", "Show"),
                        new SubcommandData("add", "Add"), new SubcommandData("update", "Update"))
                .setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM);
    }

    private static SlashCommandData guildBoard() {
        return Commands.slash("board", "Board").addSubcommands(new SubcommandData("list", "List"),
                        new SubcommandData("start", "Start"))
                .setContexts(InteractionContextType.GUILD);
    }

    private static SlashCommandData everywhere(String name) {
        return Commands.slash(name, "Do " + name)
                .setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM);
    }

    private static SlashCommandData admin() {
        return Commands.slash("admin", "Do admin")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .setContexts(InteractionContextType.GUILD);
    }

    private static SlashCommandData ownerOnly() {
        return Commands.slash("owner", "Do owner")
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
                .setContexts(InteractionContextType.GUILD);
    }

    @Test
    public void sortedByName() {
        assertEquals(HelpCommand.lines(List.of(everywhere("ping"), everywhere("banlist")),
                        InteractionContextType.GUILD, MEMBER, NONE),
                List.of("`/banlist` – Do banlist", "`/ping` – Do ping"));
    }

    @Test
    public void memberWithoutPermissionDoesNotSeeGatedCommand() {
        assertEquals(HelpCommand.lines(List.of(everywhere("ping"), admin()), InteractionContextType.GUILD, MEMBER, NONE),
                List.of("`/ping` – Do ping"));
    }

    @Test
    public void managerSeesGatedCommand() {
        assertEquals(HelpCommand.lines(List.of(admin()), InteractionContextType.GUILD, MANAGER, NONE),
                List.of("`/admin` – Do admin"));
    }

    @Test
    public void administratorSeesGatedCommands() {
        assertEquals(HelpCommand.lines(List.of(admin(), ownerOnly()), InteractionContextType.GUILD, ADMIN, NONE),
                List.of("`/admin` – Do admin", "`/owner` – Do owner"));
    }

    @Test
    public void dmListsAllAndMarksServerOnly() {
        var serverOnly = Commands.slash("guildy", "Do guildy").setContexts(InteractionContextType.GUILD);
        assertEquals(HelpCommand.lines(List.of(admin(), serverOnly, everywhere("ping")),
                        InteractionContextType.BOT_DM, 0, NONE),
                List.of("`/admin` – Do admin *(servers only, needs Manage Server)*",
                        "`/guildy` – Do guildy *(servers only)*",
                        "`/ping` – Do ping"));
    }

    @Test
    public void memberWithoutManageServerDoesNotSeeBotCheckedSubcommands() {
        assertEquals(HelpCommand.lines(List.of(board()), InteractionContextType.GUILD, MEMBER, CHECKED),
                List.of("`/board get` – Show"));
    }

    @Test
    public void managerAndAdministratorSeeBotCheckedSubcommands() {
        List<String> all = List.of("`/board add` – Add", "`/board get` – Show", "`/board update` – Update");
        for (long permissions : new long[]{MANAGER, ADMIN}) {
            assertEquals(HelpCommand.lines(List.of(board()), InteractionContextType.GUILD, permissions, CHECKED).stream()
                    .sorted().toList(), all);
        }
    }

    @Test
    public void tournamentStyleListStaysVisibleButStartIsHidden() {
        Map<String, Set<String>> checked = Map.of("board", Set.of("start"));
        assertEquals(HelpCommand.lines(List.of(guildBoard()), InteractionContextType.GUILD, MEMBER, checked),
                List.of("`/board list` – List"));
    }

    @Test
    public void dmMarksBotCheckedSubcommands() {
        assertEquals(HelpCommand.lines(List.of(board()), InteractionContextType.BOT_DM, 0, CHECKED), List.of(
                "`/board get` – Show",
                "`/board add` – Add *(servers only, needs Manage Server)*",
                "`/board update` – Update *(servers only, needs Manage Server)*"));
    }

    @Test
    public void dmOfServerOnlyCommandMarksCheckedAndOthersDifferently() {
        Map<String, Set<String>> checked = Map.of("board", Set.of("start"));
        assertEquals(HelpCommand.lines(List.of(guildBoard()), InteractionContextType.BOT_DM, 0, checked), List.of(
                "`/board list` – List *(servers only)*",
                "`/board start` – Start *(servers only, needs Manage Server)*"));
    }

    @Test
    public void contextFilterUsesGetContexts() {
        var dmOnly = Commands.slash("dmonly", "Do dmonly").setContexts(InteractionContextType.BOT_DM);
        assertEquals(HelpCommand.lines(List.of(dmOnly), InteractionContextType.GUILD, ADMIN, NONE), List.of());
    }

    @Test
    public void readsCommandsLazily() {
        List<SlashCommandData> registered = new ArrayList<>();
        HelpCommand help = new HelpCommand(() -> registered, Map::of);
        registered.add(help.data());
        registered.add(everywhere("ping"));
        assertEquals(HelpCommand.lines(help.commands(), InteractionContextType.GUILD, MEMBER, NONE).size(), 2);
    }

    @Test
    public void definitionIsGlobalAndOptionless() {
        var data = new HelpCommand(List::of, Map::of).data();
        assertEquals(data.getName(), "help");
        assertTrue(data.getOptions().isEmpty());
        assertTrue(data.getContexts().contains(InteractionContextType.GUILD));
        assertTrue(data.getContexts().contains(InteractionContextType.BOT_DM));
    }
}
