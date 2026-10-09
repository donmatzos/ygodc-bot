package at.magi.ygodiscordbot.impl.deck;

import at.magi.ygodiscordbot.entity.card.CardNames;
import at.magi.ygodiscordbot.entity.deck.Decklist;
import at.magi.ygodiscordbot.entity.deck.Ydke;
import at.magi.ygodiscordbot.entity.deck.YdkeDeck;
import at.magi.ygodiscordbot.impl.card.CardRepository;
import at.magi.ygodiscordbot.impl.command.DatabaseReplies;
import at.magi.ygodiscordbot.impl.command.SlashCommand;
import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * {@code /deck save|get|update|delete|list}: each user's own decklists, stored as YDKE URIs.
 *
 * <p>Input is validated right away; database work runs on {@code dbExecutor} so the JDA event thread
 * never waits for the database. All replies are only visible to the user.
 */
public final class DeckCommand implements SlashCommand {

    static final int MAX_MAIN = 60;
    static final int MAX_EXTRA = 15;
    static final int MAX_SIDE = 15;

    static final String UNAVAILABLE = "Deck storage is not available right now. Please try again later.";
    static final String BUSY = "Too many deck requests right now. Please try again in a moment.";
    private static final DatabaseReplies.Texts TEXTS = new DatabaseReplies.Texts(BUSY, UNAVAILABLE);

    private static final String NAME = "name";
    private static final String YDKE = "ydke";

    private final DecklistRepository decks;
    private final CardRepository cards;
    private final Executor dbExecutor;

    public DeckCommand(DecklistRepository decks, CardRepository cards, Executor dbExecutor) {
        this.decks = decks;
        this.cards = cards;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("deck", "Save and load your decklists (YDKE)")
                .addSubcommands(
                        new SubcommandData("save", "Save a new deck").addOptions(nameOption(), ydkeOption()),
                        new SubcommandData("get", "Show one of your decks").addOptions(nameOption()),
                        new SubcommandData("update", "Replace a saved deck").addOptions(nameOption(), ydkeOption()),
                        new SubcommandData("delete", "Delete one of your decks").addOptions(nameOption()),
                        new SubcommandData("list", "List your saved decks"))
                .setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM);
    }

    private static OptionData nameOption() {
        return new OptionData(OptionType.STRING, NAME, "Deck name", true)
                .setMaxLength(Decklist.MAX_NAME_LENGTH);
    }

    private static OptionData ydkeOption() {
        return new OptionData(OptionType.STRING, YDKE, "Deck as YDKE URI: " + Ydke.FORMAT, true)
                .setMaxLength(Decklist.MAX_YDKE_LENGTH);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        String subcommand = event.getSubcommandName();
        long userId = event.getUser().getIdLong();
        if ("list".equals(subcommand)) {
            runInDatabase(event, () -> listReply(decks.names(userId)));
            return;
        }

        String name = event.getOption(NAME, "", OptionMapping::getAsString).strip();
        if (name.isEmpty()) {
            replyError(event, "The deck name must not be empty.");
            return;
        }
        switch (subcommand) {
            case "save", "update" -> {
                String ydke = event.getOption(YDKE, "", OptionMapping::getAsString).strip();
                String error = validateYdke(ydke);
                if (error != null) {
                    replyError(event, error);
                    return;
                }
                if (subcommand.equals("save")) {
                    runInDatabase(event, () -> save(userId, name, ydke));
                } else {
                    runInDatabase(event, () -> decks.update(userId, name, ydke)
                            .map(deck -> deckReply("You updated the following deck", deck, cards.names()))
                            .orElseGet(() -> List.of(notFound(name))));
                }
            }
            case "get" -> runInDatabase(event, () -> decks.find(userId, name)
                    .map(deck -> deckReply("Showing deck", deck, cards.names()))
                    .orElseGet(() -> List.of(notFound(name))));
            case "delete" -> runInDatabase(event, () -> decks.delete(userId, name)
                    .map(deck -> deckReply("You deleted the following deck", deck, cards.names()))
                    .orElseGet(() -> List.of(notFound(name))));
            default -> replyError(event, "Unknown subcommand.");
        }
    }

    private List<String> save(long userId, String name, String ydke) throws SQLException {
        DecklistRepository.SaveResult result = decks.create(userId, name, ydke);
        if (result != DecklistRepository.SaveResult.SAVED) {
            return List.of(saveError(result, name));
        }
        // Show the deck as stored
        return decks.find(userId, name)
                .map(deck -> deckReply("You saved the following deck", deck, cards.names()))
                .orElseGet(() -> List.of(notFound(name)));
    }

    /** Returns an error message for the user, or null if the URI is a valid deck. */
    static String validateYdke(String ydke) {
        String usage = "\nExpected format: `" + Ydke.FORMAT + "`";
        if (!ydke.startsWith(Ydke.PREFIX)) {
            return "❌ The deck must be a YDKE URI starting with `" + Ydke.PREFIX + "`." + usage;
        }
        YdkeDeck deck;
        try {
            deck = Ydke.parse(ydke);
        } catch (IllegalArgumentException e) {
            return "❌ Invalid YDKE URI: " + e.getMessage() + "." + usage;
        }
        if (deck.main().isEmpty() && deck.extra().isEmpty() && deck.side().isEmpty()) {
            return "❌ The deck contains no cards." + usage;
        }
        if (deck.main().size() > MAX_MAIN || deck.extra().size() > MAX_EXTRA || deck.side().size() > MAX_SIDE) {
            return "❌ Too many cards: at most " + MAX_MAIN + " Main, " + MAX_EXTRA + " Extra and "
                    + MAX_SIDE + " Side Deck cards (this deck: " + counts(deck) + ").";
        }
        return null;
    }

    static String saveError(DecklistRepository.SaveResult result, String name) {
        return switch (result) {
            case SAVED -> throw new IllegalArgumentException("not an error");
            case NAME_TAKEN -> "❌ You already have a deck named **" + name + "**. Use `/deck update` to replace it.";
            case LIMIT_REACHED -> "❌ You can save up to " + DecklistRepository.MAX_DECKS_PER_USER
                    + " decks. Delete one with `/deck delete` first.";
        };
    }

    /** E.g. "You saved the following deck **Dragons**:" followed by the card list and the YDKE URI. */
    static List<String> deckReply(String action, Decklist deck, CardNames names) {
        // Stored URIs were validated on save
        return DeckMessages.deck(action + " **" + deck.name() + "**:", deck.updatedAt(), deck.ydke(),
                Ydke.parse(deck.ydke()), names);
    }

    /** 50 decks with 50-character names exceed one Discord message, so long lists are split. */
    static List<String> listReply(List<String> names) {
        if (names.isEmpty()) {
            return List.of("You have no saved decks. Save one with `/deck save`.");
        }
        return DcMessageUtils.packLines("Your decks (" + names.size() + "):",
                names.stream().map(name -> "• " + name).toList());
    }

    private static String counts(YdkeDeck deck) {
        return "Main " + deck.main().size() + " · Extra " + deck.extra().size() + " · Side " + deck.side().size();
    }

    private static String notFound(String name) {
        return "❌ You have no deck named **" + name + "**. See `/deck list`.";
    }

    private static void replyError(SlashCommandInteractionEvent event, String message) {
        event.reply(message).setEphemeral(true).queue();
    }

    /** Runs only after Discord accepted the defer, so a timed-out /deck save is not still saved in the background. */
    private void runInDatabase(SlashCommandInteractionEvent event, DatabaseReplies.SqlListCall work) {
        DatabaseReplies.replyAllEphemeral(event, dbExecutor, TEXTS, work);
    }
}
