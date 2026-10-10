package at.magi.ygodiscordbot.impl.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.Set;

public interface SlashCommand {

    /** Definition sent to Discord: name, description and options. */
    SlashCommandData data();

    void execute(SlashCommandInteractionEvent event);

    /**
     * Subcommands (top-level names) that the bot itself restricts to members with Manage Server, because Discord
     * can't gate single subcommands. /help uses this to hide or mark them.
     */
    default Set<String> botCheckedManageServer() {
        return Set.of();
    }
}
