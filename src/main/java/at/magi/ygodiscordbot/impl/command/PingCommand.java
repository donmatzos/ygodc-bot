package at.magi.ygodiscordbot.impl.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

/** Example command to check that the bot is up. */
public final class PingCommand implements SlashCommand {

    @Override
    public SlashCommandData data() {
        return Commands.slash("ping", "Check whether the bot is alive");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        long gatewayPing = event.getJDA().getGatewayPing();
        event.reply("Pong! Gateway ping: " + gatewayPing + " ms").setEphemeral(true).queue();
    }
}
