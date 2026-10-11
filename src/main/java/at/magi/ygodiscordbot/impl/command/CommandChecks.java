package at.magi.ygodiscordbot.impl.command;

import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What the slash commands share when they refuse a call: the Manage Server check, refusal replies and fixed texts. */
public final class CommandChecks {

    private static final Logger log = LoggerFactory.getLogger(CommandChecks.class);

    /** Shown when the database request queue is full (see {@link DatabaseReplies.Texts}). */
    public static final String BUSY = "Too many requests right now. Please try again in a moment.";

    static final String UNKNOWN_SUBCOMMAND = "Unknown subcommand.";

    private static final long MANAGE_SERVER_BITS =
            Permission.MANAGE_SERVER.getRawValue() | Permission.ADMINISTRATOR.getRawValue();

    private CommandChecks() {
    }

    /** True if the raw permission set holds Manage Server or Administrator. */
    public static boolean canManageServer(long permissionsRaw) {
        return (permissionsRaw & MANAGE_SERVER_BITS) != 0;
    }

    /** True if the caller has Manage Server in this server (false in the bot DM, where there is no member). */
    public static boolean canManageServer(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }

    /** Logs the refusal with its reason and tells only the caller why. */
    public static void refuse(SlashCommandInteractionEvent event, String problem) {
        log.info("/{} refused for {}: {}", event.getFullCommandName(), MessageSender.who(event), problem);
        event.reply(problem).setEphemeral(true).queue();
    }

    public static void unknownSubcommand(SlashCommandInteractionEvent event) {
        event.reply(UNKNOWN_SUBCOMMAND).setEphemeral(true).queue();
    }
}
