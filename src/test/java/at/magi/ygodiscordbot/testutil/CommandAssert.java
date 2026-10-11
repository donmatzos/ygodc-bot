package at.magi.ygodiscordbot.testutil;

import at.magi.ygodiscordbot.impl.command.SlashCommand;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.List;
import java.util.Set;

import static org.testng.Assert.assertEquals;

/** Assertions on the definition (permissions, contexts, subcommands) of a slash command. */
public final class CommandAssert {

    private CommandAssert() {
    }

    public static void assertManageServerOnly(SlashCommandData data) {
        assertEquals(data.getDefaultPermissions().getPermissionsRaw(),
                DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER).getPermissionsRaw(),
                "default permissions of /" + data.getName());
    }

    public static void assertOpenToEveryone(SlashCommandData data) {
        assertEquals(data.getDefaultPermissions(), DefaultMemberPermissions.ENABLED,
                "default permissions of /" + data.getName());
    }

    public static void assertGuildOnly(SlashCommandData data) {
        assertEquals(data.getContexts(), Set.of(InteractionContextType.GUILD), "contexts of /" + data.getName());
    }

    /** The subcommand names, in order. */
    public static void assertSubcommands(SlashCommandData data, String... names) {
        assertEquals(data.getSubcommands().stream().map(SubcommandData::getName).toList(), List.of(names),
                "subcommands of /" + data.getName());
    }

    public static void assertBotCheckedManageServer(SlashCommand command, String... subcommands) {
        assertEquals(command.botCheckedManageServer(), Set.of(subcommands));
    }

    public static SubcommandData sub(SlashCommandData data, String name) {
        return data.getSubcommands().stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("/" + data.getName() + " has no subcommand '" + name + "', has "
                        + data.getSubcommands().stream().map(SubcommandData::getName).toList()));
    }

    public static OptionData option(SubcommandData sub, String name) {
        return sub.getOptions().stream().filter(o -> o.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("/" + sub.getName() + " has no option '" + name + "', has "
                        + sub.getOptions().stream().map(OptionData::getName).toList()));
    }
}
