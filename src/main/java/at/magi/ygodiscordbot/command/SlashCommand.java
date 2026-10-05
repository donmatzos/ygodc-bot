package at.magi.ygodiscordbot.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public interface SlashCommand {

    /** Definition sent to Discord: name, description and options. */
    SlashCommandData data();

    void execute(SlashCommandInteractionEvent event);
}
