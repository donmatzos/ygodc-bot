package at.magi.ygodiscordbot.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Holds all slash commands and routes incoming interactions to the matching one. */
public final class CommandRegistry extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

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
}
