package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.impl.command.DatabaseReplies;
import at.magi.ygodiscordbot.impl.command.SlashCommand;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.concurrent.Executor;

/**
 * {@code /match finish id winner} and {@code /match doubleloss id} (time ran out, both lose) for the two players of a
 * match, and {@code /match-admin finish|doubleloss} (default Manage Server) for organizers, who may report any match
 * and correct finished ones while the round is open.
 */
public final class MatchCommand implements SlashCommand {

    private static final String ID = "id";
    private static final String WINNER = "winner";

    private final TournamentService service;
    private final Executor dbExecutor;
    private final boolean admin;

    private MatchCommand(TournamentService service, Executor dbExecutor, boolean admin) {
        this.service = service;
        this.dbExecutor = dbExecutor;
        this.admin = admin;
    }

    public static MatchCommand forPlayers(TournamentService service, Executor dbExecutor) {
        return new MatchCommand(service, dbExecutor, false);
    }

    public static MatchCommand forAdmins(TournamentService service, Executor dbExecutor) {
        return new MatchCommand(service, dbExecutor, true);
    }

    @Override
    public SlashCommandData data() {
        SlashCommandData data = Commands.slash(admin ? "match-admin" : "match",
                        admin ? "Report or correct tournament match results (organizers)"
                                : "Report your tournament match result")
                .addSubcommands(
                        new SubcommandData("finish", admin ? "Set or correct the winner of any open-round match"
                                : "Report the winner of your match")
                                .addOptions(idOption(), new OptionData(OptionType.USER, WINNER, "Who won", true)),
                        new SubcommandData("doubleloss", admin ? "Set or correct a double loss for any open-round match"
                                : "Time ran out without a winner: both players lose")
                                .addOptions(idOption()))
                .setContexts(InteractionContextType.GUILD);
        return admin ? data.setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)) : data;
    }

    private static OptionData idOption() {
        return new OptionData(OptionType.INTEGER, ID, "Match ID from the tournament post", true)
                .setRequiredRange(TournamentService.MIN_MATCH_ID, TournamentService.MAX_MATCH_ID);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        String subcommand = event.getSubcommandName();
        if (!"finish".equals(subcommand) && !"doubleloss".equals(subcommand)) {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }
        int id = event.getOption(ID, 0, OptionMapping::getAsInt);
        long guild = event.getGuild().getIdLong();
        long caller = event.getUser().getIdLong();
        if ("doubleloss".equals(subcommand)) {
            DatabaseReplies.replyEphemeral(event, dbExecutor, TournamentCommand.TEXTS,
                    () -> service.doubleLoss(id, guild, caller, admin));
            return;
        }
        long winner = event.getOption(WINNER, OptionMapping::getAsUser).getIdLong();
        DatabaseReplies.replyEphemeral(event, dbExecutor, TournamentCommand.TEXTS,
                () -> service.finishMatch(id, guild, caller, winner, admin));
    }
}
