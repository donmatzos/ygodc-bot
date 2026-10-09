package at.magi.ygodiscordbot.impl.help;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class HelpCommandTest {

    private static final long MEMBER = Permission.getRaw(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND);
    private static final long MANAGER = MEMBER | Permission.MANAGE_SERVER.getRawValue();
    private static final long ADMIN = Permission.ADMINISTRATOR.getRawValue();

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
                        InteractionContextType.GUILD, MEMBER),
                List.of("`/banlist` – Do banlist", "`/ping` – Do ping"));
    }

    @Test
    public void memberWithoutPermissionDoesNotSeeGatedCommand() {
        assertEquals(HelpCommand.lines(List.of(everywhere("ping"), admin()), InteractionContextType.GUILD, MEMBER),
                List.of("`/ping` – Do ping"));
    }

    @Test
    public void managerSeesGatedCommand() {
        assertEquals(HelpCommand.lines(List.of(admin()), InteractionContextType.GUILD, MANAGER),
                List.of("`/admin` – Do admin"));
    }

    @Test
    public void administratorSeesGatedCommands() {
        assertEquals(HelpCommand.lines(List.of(admin(), ownerOnly()), InteractionContextType.GUILD, ADMIN),
                List.of("`/admin` – Do admin", "`/owner` – Do owner"));
    }

    @Test
    public void dmListsAllAndMarksServerOnly() {
        var serverOnly = Commands.slash("guildy", "Do guildy").setContexts(InteractionContextType.GUILD);
        assertEquals(HelpCommand.lines(List.of(admin(), serverOnly, everywhere("ping")),
                        InteractionContextType.BOT_DM, 0),
                List.of("`/admin` – Do admin *(servers only, needs Manage Server)*",
                        "`/guildy` – Do guildy *(servers only)*",
                        "`/ping` – Do ping"));
    }

    @Test
    public void contextFilterUsesGetContexts() {
        var dmOnly = Commands.slash("dmonly", "Do dmonly").setContexts(InteractionContextType.BOT_DM);
        assertEquals(HelpCommand.lines(List.of(dmOnly), InteractionContextType.GUILD, ADMIN), List.of());
    }

    @Test
    public void readsCommandsLazily() {
        List<SlashCommandData> registered = new ArrayList<>();
        HelpCommand help = new HelpCommand(() -> registered);
        registered.add(help.data());
        registered.add(everywhere("ping"));
        assertEquals(HelpCommand.lines(help.commands(), InteractionContextType.GUILD, MEMBER).size(), 2);
    }

    @Test
    public void definitionIsGlobalAndOptionless() {
        var data = new HelpCommand(List::of).data();
        assertEquals(data.getName(), "help");
        assertTrue(data.getOptions().isEmpty());
        assertTrue(data.getContexts().contains(InteractionContextType.GUILD));
        assertTrue(data.getContexts().contains(InteractionContextType.BOT_DM));
    }
}
