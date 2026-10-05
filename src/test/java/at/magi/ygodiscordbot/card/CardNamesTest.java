package at.magi.ygodiscordbot.card;

import org.testng.annotations.Test;

import java.util.Optional;

import static org.testng.Assert.assertEquals;

public class CardNamesTest {

    @Test
    public void looksUpNamesIncludingArtworks() {
        CardNames names = TestCards.names();
        assertEquals(names.size(), 5);
        assertEquals(names.name(TestCards.BLUE_EYES), Optional.of("Blue-Eyes White Dragon"));
        assertEquals(names.name(TestCards.BLUE_EYES_ALT), Optional.of("Blue-Eyes White Dragon"));
        assertEquals(names.name(TestCards.DARK_MAGICIAN), Optional.of("Dark Magician"));
    }

    @Test
    public void unknownPasscodes() {
        CardNames names = TestCards.names();
        assertEquals(names.name(12345678L), Optional.empty());
        assertEquals(names.name(4294967295L), Optional.empty()); // unsigned 32-bit, beyond int
        assertEquals(names.name(-1L), Optional.empty());
        assertEquals(CardNames.EMPTY.name(TestCards.BLUE_EYES), Optional.empty());
    }
}
