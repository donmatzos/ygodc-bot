package at.magi.ygodiscordbot.impl.card;

import at.magi.ygodiscordbot.entity.card.CardCatalog;
import at.magi.ygodiscordbot.entity.card.CardNames;
import at.magi.ygodiscordbot.entity.card.TestCards;
import at.magi.ygodiscordbot.testutil.TempDirTest;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class CardFileStoreTest extends TempDirTest {

    private CardFileStore store;

    @BeforeMethod
    public void setUp() throws IOException {
        store = new CardFileStore(dir.resolve("cards.json"));
    }

    @Test
    public void roundTrip() throws IOException {
        Instant checked = Instant.parse("2026-10-05T12:00:00Z");
        CardNames names = CardNames.of(Map.of(1, "Plain", 2, "With \"quotes\" and \\\\ backslash", 3, "Ünïcödé ★"));
        store.save(new CardCatalog("147.22", checked, names));

        CardCatalog loaded = store.load().orElseThrow();
        assertEquals(loaded.version(), "147.22");
        assertEquals(loaded.checkedAt(), checked);
        assertEquals(loaded.names().size(), 3);
        assertEquals(loaded.names().name(2), Optional.of("With \"quotes\" and \\\\ backslash"));
        assertEquals(loaded.names().name(3), Optional.of("Ünïcödé ★"));
    }

    @Test
    public void missingFileIsEmpty() {
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void corruptOrIncompleteFileIsEmpty() throws IOException {
        Files.writeString(dir.resolve("cards.json"), "{\"version\":\"1\",\"cards\":{\"1\":");
        assertTrue(store.load().isEmpty());
        Files.writeString(dir.resolve("cards.json"), "{\"version\":\"1\",\"cards\":{}}"); // no checkedAt
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void loadDeletesTempFilesOfAKilledWrite() throws IOException {
        store.save(TestCards.catalog("1", Instant.EPOCH));
        Files.writeString(dir.resolve("cards98765.tmp"), "{\"version\":");

        assertTrue(store.load().isPresent());
        assertEquals(fileNames(), List.of("cards.json"));
    }
}
