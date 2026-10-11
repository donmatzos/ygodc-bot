package at.magi.ygodiscordbot.impl.deck;

import at.magi.ygodiscordbot.entity.card.TestCards;
import at.magi.ygodiscordbot.entity.deck.Decklist;
import at.magi.ygodiscordbot.entity.deck.Ydke;
import at.magi.ygodiscordbot.entity.deck.YdkeDeck;
import at.magi.ygodiscordbot.impl.card.CardRepository;
import at.magi.ygodiscordbot.impl.deck.DecklistRepository.SaveResult;
import at.magi.ygodiscordbot.testutil.FakeInteractions;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static at.magi.ygodiscordbot.testutil.DiscordAssert.assertSendable;
import static at.magi.ygodiscordbot.testutil.FakeInteractions.returns;
import static at.magi.ygodiscordbot.testutil.FakeInteractions.stringOption;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class DeckCommandTest {

    private static String deck(int main, int extra, int side) {
        return Ydke.encode(new YdkeDeck(Collections.nCopies(main, 89631139L),
                Collections.nCopies(extra, 23995346L), Collections.nCopies(side, 55144522L)));
    }

    @Test
    public void acceptsValidDeck() {
        assertNull(DeckCommand.validateYdke(deck(40, 15, 15)));
        assertNull(DeckCommand.validateYdke(deck(60, 15, 15)));
    }

    @DataProvider
    public Object[][] rejectedYdkes() {
        return new Object[][]{
                {"wrong prefix", "https://example.com/deck", "ydke://", true},
                {"malformed uri", "ydke://not*base64!!!", "Base64", true},
                {"empty deck", "ydke://!!!", "no cards", false},
                {"61 main cards", deck(61, 0, 0), "Too many cards", false},
                {"16 extra cards", deck(40, 16, 0), "Too many cards", false},
                {"16 side cards", deck(40, 0, 16), "Too many cards", false},
        };
    }

    @Test(dataProvider = "rejectedYdkes")
    public void rejectsInvalidDecks(String name, String ydke, String reason, boolean showsFormat) {
        String error = DeckCommand.validateYdke(ydke);
        assertTrue(error.contains(reason), name + ": " + error);
        if (showsFormat) {
            assertTrue(error.contains(Ydke.FORMAT), name + ": " + error);
        }
    }

    /** Records what reaches the database; finds nothing, so a successful save or update ends in "no deck named". */
    private static final class RecordingDecks extends DecklistRepository {
        final List<String> stored = new ArrayList<>();

        RecordingDecks() {
            super(null, Clock.systemUTC());
        }

        @Override
        public SaveResult create(long userId, String name, String ydke) {
            stored.add(ydke);
            return SaveResult.SAVED;
        }

        @Override
        public Optional<Decklist> update(long userId, String name, String ydke) {
            stored.add(ydke);
            return Optional.empty();
        }

        @Override
        public Optional<Decklist> find(long userId, String name) {
            return Optional.empty();
        }
    }

    @DataProvider
    public Object[][] storingSubcommands() {
        return new Object[][]{{"save"}, {"update"}};
    }

    @Test(dataProvider = "storingSubcommands")
    public void canonicalYdkeIsWhatGetsStored(String subcommand) {
        RecordingDecks decks = new RecordingDecks();
        FakeInteractions fakes = new FakeInteractions();
        new DeckCommand(decks, new CardRepository(), Runnable::run).execute(fakes.event(Map.of(
                "getName", returns("deck"), "getSubcommandName", returns(subcommand),
                "getOptions", returns(List.of(stringOption("name", "Dragons", Map.of()),
                        stringOption("ydke", "ydke://o6lXBQ==!!", Map.of())))))); // one "!" short
        assertEquals(decks.stored, List.of("ydke://o6lXBQ==!!!"), subcommand);
        // Nothing was found afterwards: the reply names the deck in bold
        assertTrue(fakes.sent().toString().contains("You have no deck named **Dragons**."), fakes.sent().toString());
    }

    @Test
    public void deckReplyShowsTheCanonicalYdkeForOldRows() {
        // a row saved before junk after the third "!" was rejected, but which still parses
        Decklist deck = new Decklist(1, 2, "Dragons", "ydke://o6lXBQ==!!", 0, 1_760_000_000_000L);
        String reply = String.join("\n", DeckCommand.deckReply("Showing deck", DeckCommand.Again.UPDATE, deck, TestCards.names()));
        assertTrue(reply.contains("```\nydke://o6lXBQ==!!!\n```"), reply);
    }

    @Test
    public void deckReplyForAnUnparsableStoredRowOnlyNamesTheDeck() {
        Decklist deck = new Decklist(1, 2, "Dragons", "ydke://o6lXBQ==!!!junk", 0, 1_760_000_000_000L);
        // After a delete the deck is gone, so /deck update would answer "no deck named"
        Map<String, DeckCommand.Again> hints = Map.of("Showing deck", DeckCommand.Again.UPDATE,
                "You deleted the following deck", DeckCommand.Again.SAVE);
        hints.forEach((action, again) -> {
            String command = again == DeckCommand.Again.UPDATE ? "`/deck update`" : "`/deck save`";
            assertEquals(DeckCommand.deckReply(action, again, deck, TestCards.names()),
                    List.of(action + " **Dragons**:\n"
                            + "This deck's stored YDKE is invalid; save it again with " + command + "."));
        });
    }

    @Test
    public void deckReplyNamesTheOperationAndListsTheCards() {
        String ydke = Ydke.encode(new YdkeDeck(List.of(TestCards.BLUE_EYES, TestCards.BLUE_EYES), List.of(), List.of()));
        Decklist deck = new Decklist(1, 2, "Dragons", ydke, 0, 1_760_000_000_000L);

        for (String action : List.of("You saved the following deck", "You updated the following deck",
                "You deleted the following deck", "Showing deck")) {
            String reply = String.join("\n", DeckCommand.deckReply(action, DeckCommand.Again.UPDATE, deck, TestCards.names()));
            assertTrue(reply.startsWith(action + " **Dragons**:\nMain 2 · Extra 0 · Side 0"), reply);
            assertTrue(reply.contains("2x Blue-Eyes White Dragon"), reply);
            assertTrue(reply.contains(ydke), reply);
        }
    }

    @Test
    public void replies() {
        assertTrue(DeckCommand.saveError(SaveResult.NAME_TAKEN, "X").contains("/deck update"));
        assertTrue(DeckCommand.listReply(List.of()).get(0).contains("no saved decks"));
        assertEquals(DeckCommand.listReply(List.of("A", "B")), List.of("Your decks (2):\n• A\n• B"));
    }

    @Test
    public void longDeckListIsSplitIntoValidMessages() {
        // the most a user can have: 50 decks with 50-character names
        List<String> names = IntStream.range(0, DecklistRepository.MAX_DECKS_PER_USER)
                .mapToObj(i -> String.format("%02d", i) + "N".repeat(Decklist.MAX_NAME_LENGTH - 2))
                .toList();

        List<String> messages = DeckCommand.listReply(names);

        assertTrue(messages.size() > 1);
        assertTrue(messages.get(0).startsWith("Your decks (50):"));
        assertSendable(messages);
        assertEquals(String.join("\n", messages).lines().filter(line -> line.startsWith("• ")).count(), 50L);
    }

    @Test
    public void deckNamesAreMarkdownEscapedInReplies() {
        String name = "**x||y||";
        String escaped = "**x\\||y\\||";
        Decklist deck = new Decklist(1, 2, name, "ydke://o6lXBQ==!!", 0, 1_760_000_000_000L);
        assertTrue(DeckCommand.deckReply("Showing deck", DeckCommand.Again.UPDATE, deck, TestCards.names()).get(0)
                .contains("Showing deck **" + escaped + "**:"));
        Decklist broken = new Decklist(1, 2, name, "junk", 0, 1_760_000_000_000L);
        assertTrue(DeckCommand.deckReply("Showing deck", DeckCommand.Again.UPDATE, broken, TestCards.names()).get(0)
                .contains("Showing deck **" + escaped + "**:"));
        assertTrue(DeckCommand.saveError(SaveResult.NAME_TAKEN, name).contains("named **" + escaped + "**"));
        assertEquals(DeckCommand.listReply(List.of(name)), List.of("Your decks (1):\n• " + escaped));
    }
}
