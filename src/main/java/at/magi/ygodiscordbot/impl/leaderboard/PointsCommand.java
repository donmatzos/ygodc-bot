package at.magi.ygodiscordbot.impl.leaderboard;

import at.magi.ygodiscordbot.entity.leaderboard.PointChange;
import at.magi.ygodiscordbot.entity.leaderboard.Points;
import at.magi.ygodiscordbot.impl.command.DatabaseReplies;
import at.magi.ygodiscordbot.impl.command.SlashCommand;
import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * {@code /points add|remove player amount}: changes a player's points by 1–99, kept within 0 … {@link Points#MAX}.
 * Like /leaderboard-admin, who may use it is decided by Discord (default Manage Server, changeable under
 * Integrations), so the bot does not check that permission itself.
 */
public final class PointsCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(PointsCommand.class);

    private static final String AMOUNT = "amount";

    private final PlayerRepository players;
    private final Executor dbExecutor;

    public PointsCommand(PlayerRepository players, Executor dbExecutor) {
        this.players = players;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("points", "Give or take tournament points")
                .addSubcommands(
                        new SubcommandData("add", "Add points to a player (adds them to the leaderboard if needed)")
                                .addOptions(playerOption(), amountOption()),
                        new SubcommandData("remove", "Remove points from a player (stops at 0)")
                                .addOptions(playerOption(), amountOption()))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .setContexts(InteractionContextType.GUILD);
    }

    private static OptionData playerOption() {
        return new OptionData(OptionType.USER, LeaderboardCommand.PLAYER, "Player", true);
    }

    private static OptionData amountOption() {
        return new OptionData(OptionType.INTEGER, AMOUNT, "Points (1–" + Points.MAX_CHANGE + ")", true)
                .setRequiredRange(1, Points.MAX_CHANGE);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        String subcommand = event.getSubcommandName();
        if (!"add".equals(subcommand) && !"remove".equals(subcommand)) {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }
        User player = event.getOption(LeaderboardCommand.PLAYER, OptionMapping::getAsUser);
        long delta = delta(subcommand, event.getOption(AMOUNT, 0L, OptionMapping::getAsLong));
        // Discord already enforces server + permission; only the target needs checking
        String problem = LeaderboardCommand.changeProblem(true, true, player.isBot());
        if (problem != null) {
            log.info("/{} refused for {}: {}", event.getFullCommandName(), MessageSender.who(event), problem);
            event.reply(problem).setEphemeral(true).queue();
            return;
        }
        String name = player.getEffectiveName();
        DatabaseReplies.replyEphemeral(event, dbExecutor, LeaderboardCommand.TEXTS, () -> {
            PointChange change = players.changePoints(player.getIdLong(), delta);
            return change == null
                    ? LeaderboardMessages.notOnBoard(name)
                    : LeaderboardMessages.pointsChanged(name, change, delta);
        });
    }

    static long delta(String subcommand, long amount) {
        return "remove".equals(subcommand) ? -amount : amount;
    }
}
