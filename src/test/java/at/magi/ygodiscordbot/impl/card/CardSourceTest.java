package at.magi.ygodiscordbot.impl.card;

import at.magi.ygodiscordbot.entity.card.CardNames;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class CardSourceTest {

    /** Shape of cardinfo.php, with the fields the parser must skip. */
    private static final String CARDS = """
            {"data":[
              {"id":89631139,"name":"Blue-Eyes White Dragon","type":"Normal Monster","desc":"\\"Legendary\\" dragon",
               "atk":3000,"linkmarkers":["Top"],"banlist_info":{"ban_tcg":"Limited"},
               "card_sets":[{"set_name":"LOB","set_price":"1.00"}],
               "card_images":[{"id":89631139,"image_url":"a.jpg"},{"id":89631141,"image_url":"b.jpg"}],
               "card_prices":[{"cardmarket_price":"0.10"}]},
              {"id":46986414,"name":"Dark Magician","card_images":[{"id":46986414},null,{"id":"x"}]},
              {"id":"broken","name":"No Passcode"},
              {"id":12345,"name":null},
              {"name":"Missing Id","id":{"nested":1}},
              null,
              {"id":55144522,"name":"Pot of Greed","card_images":"not a list"}
            ],
            "meta":{"total_rows":5}}
            """;

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void keepsPasscodesNamesAndArtworks() throws IOException {
        CardNames names = CardSource.parseCards(json(CARDS), 3);
        assertEquals(names.size(), 4);
        assertEquals(names.name(89631139), Optional.of("Blue-Eyes White Dragon"));
        assertEquals(names.name(89631141), Optional.of("Blue-Eyes White Dragon"));
        assertEquals(names.name(46986414), Optional.of("Dark Magician"));
        assertEquals(names.name(55144522), Optional.of("Pot of Greed"));
        assertEquals(names.name(12345), Optional.empty());
    }

    @Test
    public void rejectsTooFewCards() {
        assertThrows(IOException.class, () -> CardSource.parseCards(json(CARDS), 5));
    }

    @Test
    public void rejectsMoreCardsThanTheCap() {
        assertThrows(IOException.class, () -> CardSource.parseCards(json(CARDS), 1, 2));
    }

    @Test
    public void rejectsMoreNamesThanTheCap() {
        // 4 names: 3 cards' passcodes plus one alternate artwork
        assertThrows(IOException.class, () -> CardSource.parseCards(json(CARDS), 1, 10, 3));
    }

    @Test
    public void acceptsNamesUpToTheCap() throws IOException {
        assertEquals(CardSource.parseCards(json(CARDS), 1, 10, 4).size(), 4);
    }

    @Test
    public void acceptsCardsUpToTheCap() throws IOException {
        assertEquals(CardSource.parseCards(json(CARDS), 1, 3).size(), 4);
    }

    @Test
    public void rejectsTruncatedOrInvalidResponses() {
        assertThrows(IOException.class, () -> CardSource.parseCards(json(CARDS.substring(0, 300)), 1));
        assertThrows(IOException.class, () -> CardSource.parseCards(json("[1,2,3]"), 1));
        assertThrows(IOException.class, () -> CardSource.parseCards(json("{\"error\":\"No card matching\"}"), 1));
    }

    @Test
    public void parsesVersion() throws IOException {
        byte[] json = "[{\"database_version\":\"147.22\",\"last_update\":\"2026-10-02 00:03:40\"}]"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(CardSource.parseVersion(json), "147.22");
        assertThrows(IOException.class, () -> CardSource.parseVersion("[]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void capsOverLongNames() throws IOException {
        String text = "{\"data\":[{\"id\":1,\"name\":\"" + "x".repeat(5000) + "\"}]}";
        CardNames names = CardSource.parseCards(json(text), 1);
        assertEquals(names.name(1), Optional.of("x".repeat(199) + "…"));
    }
}
