package at.magi.ygodiscordbot.entity.deck;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class YdkeTest {

    // Blue-Eyes White Dragon x3, Dark Magician | Blue-Eyes Ultimate Dragon | Pot of Greed
    public static final YdkeDeck DECK = new YdkeDeck(
            List.of(89631139L, 89631139L, 89631139L, 46986414L), List.of(23995346L), List.of(55144522L));

    @Test
    public void roundTrip() {
        String uri = Ydke.encode(DECK);
        assertTrue(uri.startsWith("ydke://"));
        assertTrue(uri.endsWith("!"));
        assertEquals(Ydke.parse(uri), DECK);
    }

    @Test
    public void decodesLittleEndianPasscodes() {
        // 89631139 = 0x0557A9A3 -> bytes A3 A9 57 05
        assertEquals(Ydke.parse("ydke://o6lXBQ==!!!").main(), List.of(89631139L));
    }

    @Test
    public void passcodesAreUnsigned() {
        YdkeDeck deck = new YdkeDeck(List.of(4294967295L), List.of(), List.of());
        assertEquals(Ydke.parse(Ydke.encode(deck)).main(), List.of(4294967295L));
    }

    @Test
    public void emptyZones() {
        YdkeDeck deck = Ydke.parse("ydke://!!!");
        assertTrue(deck.main().isEmpty() && deck.extra().isEmpty() && deck.side().isEmpty());
    }

    @Test
    public void rejectsWrongPrefix() {
        assertThrows(IllegalArgumentException.class, () -> Ydke.parse("https://o5pXBQ==!!!"));
        assertThrows(IllegalArgumentException.class, () -> Ydke.parse(null));
    }

    @Test
    public void rejectsMissingSections() {
        assertThrows(IllegalArgumentException.class, () -> Ydke.parse("ydke://o5pXBQ==!"));
    }

    @DataProvider
    public Object[][] junkUris() {
        return new Object[][]{
                {"ydke://o6lXBQ==!!!junk"},
                {"ydke://o6lXBQ==!!!!"},
                {"ydke://o6lXBQ==!!!!!"},
                {"ydke://o6lXBQ==!!side!"},
                {"ydke://o6lXBQ==!!!`"},
                {"ydke://o6lXBQ==!!!\u00e4"},
        };
    }

    @Test(dataProvider = "junkUris")
    public void rejectsAnythingAfterTheThirdSeparator(String uri) {
        assertThrows(IllegalArgumentException.class, () -> Ydke.parse(uri));
    }

    @Test
    public void acceptsThreeSectionsWithoutTrailingSeparator() {
        assertEquals(Ydke.parse("ydke://o6lXBQ==!!").main(), List.of(89631139L));
    }

    @Test
    public void rejectsInvalidBase64() {
        assertThrows(IllegalArgumentException.class, () -> Ydke.parse("ydke://not*base64!!!"));
    }

    @Test
    public void rejectsPartialCard() {
        // 3 bytes
        assertThrows(IllegalArgumentException.class, () -> Ydke.parse("ydke://AAAA!!!"));
    }
}
