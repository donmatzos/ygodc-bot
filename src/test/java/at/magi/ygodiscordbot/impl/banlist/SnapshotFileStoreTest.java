package at.magi.ygodiscordbot.impl.banlist;

import at.magi.ygodiscordbot.entity.banlist.BanlistSnapshot;
import at.magi.ygodiscordbot.testutil.TempDirTest;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class SnapshotFileStoreTest extends TempDirTest {

    @Test
    public void roundTrip() throws IOException {
        SnapshotFileStore store = new SnapshotFileStore(dir.resolve("data").resolve("banlists.json"));
        BanlistSnapshot snapshot = TestLists.snapshot(Instant.parse("2026-10-05T01:00:00Z"));

        store.save(snapshot);

        assertEquals(store.load().orElseThrow(), snapshot);
    }

    @Test
    public void savesPartialSnapshot() throws IOException {
        SnapshotFileStore store = new SnapshotFileStore(dir.resolve("banlists.json"));
        BanlistSnapshot partial = new BanlistSnapshot(TestLists.tcg(Instant.EPOCH), null, null);

        store.save(partial);

        assertEquals(store.load().orElseThrow(), partial);
    }

    @Test
    public void loadDeletesTempFilesOfAKilledWrite() throws IOException {
        SnapshotFileStore store = new SnapshotFileStore(dir.resolve("banlists.json"));
        BanlistSnapshot snapshot = TestLists.snapshot(Instant.parse("2026-10-05T01:00:00Z"));
        store.save(snapshot);
        Files.writeString(dir.resolve("banlists123456.tmp"), "{\"tcg\":");
        Files.writeString(dir.resolve("other.tmp"), "not ours");

        assertEquals(store.load().orElseThrow(), snapshot);
        assertEquals(fileNames(), List.of("banlists.json", "other.tmp"));
    }

    @Test
    public void missingFileIsEmpty() {
        assertTrue(new SnapshotFileStore(dir.resolve("absent.json")).load().isEmpty());
    }

    @Test
    public void corruptFileIsEmpty() throws IOException {
        Path file = dir.resolve("banlists.json");
        Files.writeString(file, "{ not json");

        assertTrue(new SnapshotFileStore(file).load().isEmpty());
    }
}
