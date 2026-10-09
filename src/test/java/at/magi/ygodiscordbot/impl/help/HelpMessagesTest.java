package at.magi.ygodiscordbot.impl.help;

import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class HelpMessagesTest {

    @Test
    public void plainCommand() {
        assertEquals(HelpMessages.usages(Commands.slash("ping", "Check whether the bot is alive")),
                List.of("`/ping` – Check whether the bot is alive"));
    }

    @Test
    public void requiredOptionWithChoicesUsesChoiceNames() {
        var format = new OptionData(OptionType.STRING, "format", "Which list", true)
                .addChoice("TCG", "tcg").addChoice("OCG", "ocg");
        assertEquals(HelpMessages.usages(Commands.slash("banlist", "Show a list").addOptions(format)),
                List.of("`/banlist format:<TCG|OCG>` – Show a list"));
    }

    @Test
    public void optionalOptionInBrackets() {
        var page = new OptionData(OptionType.INTEGER, "page", "Page", false);
        assertEquals(HelpMessages.usages(Commands.slash("leaderboard", "Show it").addOptions(page)),
                List.of("`/leaderboard [page:<page>]` – Show it"));
    }

    @Test
    public void oneLinePerSubcommandWithItsDescription() {
        var command = Commands.slash("deck", "Decks").addSubcommands(
                new SubcommandData("get", "Show one of your decks")
                        .addOptions(new OptionData(OptionType.STRING, "name", "Deck name", true)),
                new SubcommandData("list", "List your saved decks"));
        assertEquals(HelpMessages.usages(command), List.of(
                "`/deck get name:<name>` – Show one of your decks",
                "`/deck list` – List your saved decks"));
    }

    @Test
    public void subcommandGroupsAreNotDropped() {
        var command = Commands.slash("cfg", "Config").addSubcommandGroups(
                new SubcommandGroupData("role", "Roles").addSubcommands(new SubcommandData("add", "Add a role")));
        assertEquals(HelpMessages.usages(command), List.of("`/cfg role add` – Add a role"));
    }

    @Test
    public void buildStartsWithHeaderAndSplitsLongLists() {
        assertEquals(HelpMessages.build(List.of("`/ping` – Pong")), List.of(HelpMessages.HEADER + "\n`/ping` – Pong"));
        var many = Collections.nCopies(60, "x".repeat(60));
        var messages = HelpMessages.build(many);
        assertTrue(messages.size() > 1);
        messages.forEach(m -> assertTrue(m.length() <= DcMessageUtils.MAX_MESSAGE_LENGTH));
    }
}
