package at.magi.ygodiscordbot.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Holds all slash commands and routes incoming interactions to the matching one. */
public final class CommandRegistry extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    /** Longer option values (e.g. YDKE URIs) are logged as their length only. */
    private static final int MAX_LOGGED_VALUE = 100;

    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();

    public void register(SlashCommand command) {
        String name = command.data().getName();
        if (commands.putIfAbsent(name, command) != null) {
            throw new IllegalArgumentException("Duplicate command: " + name);
        }
    }

    public List<SlashCommandData> commandData() {
        return commands.values().stream().map(SlashCommand::data).toList();
    }

    public int size() {
        return commands.size();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        log.info(callLine(event.getFullCommandName(),
                event.getOptions().stream().map(option -> Map.entry(option.getName(), option.getAsString())).toList(),
                MessageSender.who(event), event.getGuild() != null ? "server " + event.getGuild().getId() : "bot DM"));
        SlashCommand command = commands.get(event.getName());
        if (command == null) {
            event.reply("Unknown command.").setEphemeral(true).queue();
            return;
        }
        try {
            command.execute(event);
        } catch (RuntimeException e) {
            log.error("Command /{} failed", event.getName(), e);
            if (!event.isAcknowledged()) {
                event.reply("Something went wrong.").setEphemeral(true).queue();
            }
        }
    }

    /** One log line per command call; user and channel options are logged as IDs. */
    static String callLine(String command, List<Map.Entry<String, String>> options, String who, String where) {
        String arguments = options.stream()
                .map(option -> " " + option.getKey() + ":" + (option.getValue().length() > MAX_LOGGED_VALUE
                        ? "<" + option.getValue().length() + " characters>"
                        : option.getValue()))
                .collect(Collectors.joining());
        return "/" + command + arguments + " by " + who + " in " + where;
    }
}
