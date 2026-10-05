package at.magi.ygodiscordbot.card;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class CardFileStoreTest {

    private Path dir;
    private CardFileStore store;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("card-store-test");
        store = new CardFileStore(dir.resolve("cards.json"));
    }

    @AfterMethod(alwaysRun = true)
    public void deleteTempDir() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
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
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(files.map(path -> path.getFileName().toString()).toList(), List.of("cards.json"));
        }
    }
}
