package at.magi.ygodiscordbot.format;

import at.magi.ygodiscordbot.card.CardNames;
import at.magi.ygodiscordbot.card.TestCards;
import at.magi.ygodiscordbot.deck.Ydke;
import at.magi.ygodiscordbot.deck.YdkeDeck;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class DeckMessagesTest {

    private static final YdkeDeck DECK = new YdkeDeck(
            List.of(TestCards.BLUE_EYES, TestCards.DARK_MAGICIAN, TestCards.BLUE_EYES, TestCards.BLUE_EYES_ALT, 12345678L),
            List.of(23995346L), List.of());

    @Test
    public void groupsCopiesAndNamesUnknownCards() {
        String ydke = Ydke.encode(DECK);
        List<String> messages = DeckMessages.deck("Showing deck **Dragons**:", 1_760_000_000_000L, ydke, DECK,
                TestCards.names());

        assertEquals(messages.size(), 1);
        String message = messages.get(0);
        assertTrue(message.startsWith("Showing deck **Dragons**:\nMain 5 · Extra 1 · Side 0 · updated <t:1760000000:R>\n"
                + "⚠️ 1 card is not recognized"), message);
        assertTrue(message.contains("**Main Deck** · 5"), message);
        // Alternate artwork is listed separately, it is a different passcode
        assertTrue(message.contains("2x Blue-Eyes White Dragon\n1x Dark Magician\n1x Blue-Eyes White Dragon\n"
                + "1x Unknown card (12345678)"), message);
        assertTrue(message.contains("1x Blue-Eyes Ultimate Dragon"), message);
        assertFalse(message.contains("Side Deck"), message);
        assertTrue(message.endsWith("```\n" + ydke + "\n```"), message);
        assertEquals(DeckMessages.unknownCards(DECK, TestCards.names()), 1);
    }

    @Test
    public void namesNotLoadedYet() {
        List<String> messages = DeckMessages.deck("D", 0, Ydke.encode(DECK), DECK, CardNames.EMPTY);
        assertEquals(messages.size(), 1);
        assertTrue(messages.get(0).contains("not loaded yet"));
        assertFalse(messages.get(0).contains("not recognized"), "no unknown-card warning without names");
    }

    @Test
    public void unknownCardsNote() {
        YdkeDeck known = new YdkeDeck(List.of(TestCards.BLUE_EYES), List.of(), List.of());
        YdkeDeck twoUnknown = new YdkeDeck(List.of(TestCards.BLUE_EYES, 1L), List.of(2L), List.of());
        assertEquals(DeckMessages.unknownCardsNote(known, TestCards.names()), "");
        assertTrue(DeckMessages.unknownCardsNote(twoUnknown, TestCards.names()).contains("2 cards are not recognized"));
        assertEquals(DeckMessages.unknownCardsNote(twoUnknown, CardNames.EMPTY), "");
    }

    @Test
    public void largestDeckIsSplitIntoValidMessages() {
        // 90 different cards with long names
        Map<Integer, String> longNames = new HashMap<>();
        LongStream.range(0, 90).forEach(i -> longNames.put((int) (10_000_000 + i),
                "A Very Long Card Name That Goes On And On For Quite A While Number " + i));
        YdkeDeck deck = new YdkeDeck(LongStream.range(0, 60).map(i -> 10_000_000 + i).boxed().toList(),
                LongStream.range(60, 75).map(i -> 10_000_000 + i).boxed().toList(),
                LongStream.range(75, 90).map(i -> 10_000_000 + i).boxed().toList());
        String ydke = Ydke.encode(deck);

        List<String> messages = DeckMessages.deck("You saved the following deck **" + "N".repeat(50) + "**:", 0, ydke,
                deck, CardNames.of(longNames));

        assertTrue(messages.size() > 1);
        String all = String.join("\n", messages);
        for (String message : messages) {
            assertTrue(message.length() <= MessagePacker.MAX_MESSAGE_LENGTH, "message too long: " + message.length());
            assertEquals(message.split("```", -1).length % 2, 1, "unbalanced code block: " + message);
        }
        LongStream.range(0, 90).forEach(i -> assertTrue(all.contains("Number " + i + "\n"), "missing card " + i));
        assertTrue(messages.get(messages.size() - 1).contains(ydke));
    }
}
