package at.magi.ygodiscordbot.impl.help;

import at.magi.ygodiscordbot.impl.command.CommandChecks;
import at.magi.ygodiscordbot.impl.command.SlashCommand;
import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Lists the commands the user can use here, as an ephemeral reply in the channel where /help was used. */
public final class HelpCommand implements SlashCommand {

    private final Supplier<List<SlashCommandData>> commands;

    private final Supplier<Map<String, Set<String>>> botChecked;

    /**
     * @param commands   read on every call, so commands registered after /help (and /help itself) are included
     * @param botChecked per command name, the subcommands the bot restricts to Manage Server itself
     */
    public HelpCommand(Supplier<List<SlashCommandData>> commands, Supplier<Map<String, Set<String>>> botChecked) {
        this.commands = commands;
        this.botChecked = botChecked;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("help", "List all commands of this bot")
                .setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM);
    }

    List<SlashCommandData> commands() {
        return commands.get();
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        long permissions = member == null ? 0 : Permission.getRaw(member.getPermissions(event.getGuildChannel()));
        List<String> messages = HelpMessages.build(lines(commands(), event.getContext(), permissions, botChecked.get()));
        MessageSender.followUps(event.reply(messages.get(0)).setEphemeral(true), event.getHook(), messages, true)
                .queue();
    }

    /**
     * In a server: only commands usable here by this member. In the bot DM, server permissions are unknown, so
     * every command is listed and the ones that only work in servers say so. Subcommands the bot restricts to
     * Manage Server itself ({@code botChecked}) are hidden from members without it and marked in the bot DM.
     */
    static List<String> lines(List<SlashCommandData> commands, InteractionContextType context, long memberPermissions,
                              Map<String, Set<String>> botChecked) {
        boolean canManage = CommandChecks.canManageServer(memberPermissions);
        boolean inGuild = context == InteractionContextType.GUILD;
        return commands.stream()
                .sorted(Comparator.comparing(SlashCommandData::getName))
                .filter(command -> !inGuild
                        || command.getContexts().contains(context) && hasDefaultPermissions(command, memberPermissions))
                .flatMap(command -> {
                    Set<String> checked = botChecked.getOrDefault(command.getName(), Set.of());
                    String note = command.getContexts().contains(context) ? "" : serverOnlyNote(command);
                    return HelpMessages.usages(command,
                            path -> !inGuild || canManage || !checked.contains(path),
                            path -> !inGuild && checked.contains(path) ? serverOnlyNote(Permission.MANAGE_SERVER.getName()) : note
                    ).stream();
                })
                .toList();
    }

    /** Discord's default for the command; server overrides (Integrations settings) are not read. */
    static boolean hasDefaultPermissions(SlashCommandData command, long memberPermissions) {
        Long required = command.getDefaultPermissions().getPermissionsRaw();
        if (required == null || (memberPermissions & Permission.ADMINISTRATOR.getRawValue()) != 0) {
            return true;
        }
        // DISABLED (0) means administrators only
        return required != 0 && (memberPermissions & required) == required;
    }

    private static String serverOnlyNote(SlashCommandData command) {
        Long required = command.getDefaultPermissions().getPermissionsRaw();
        if (required == null) {
            return " *(servers only)*";
        }
        String names = required == 0 ? Permission.ADMINISTRATOR.getName()
                : Permission.getPermissions(required).stream().map(Permission::getName).collect(Collectors.joining(", "));
        return serverOnlyNote(names);
    }

    private static String serverOnlyNote(String needs) {
        return " *(servers only, needs " + needs + ")*";
    }
}
