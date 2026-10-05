package at.magi.ygodiscordbot.banlist;

import at.magi.ygodiscordbot.json.Json;
import at.magi.ygodiscordbot.storage.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists the last snapshot so a restart has data immediately, without waiting for the sources.
 * Written atomically (see {@link AtomicFiles}); temporary files left behind by a killed process are
 * deleted on {@link #load()}.
 */
public final class SnapshotFileStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotFileStore.class);

    public static final Path DEFAULT_FILE = Path.of("data", "banlists.json");

    private static final String TEMP_PREFIX = "banlists";

    private final Path file;

    public SnapshotFileStore(Path file) {
        this.file = file;
    }

    /** Call before the first {@link #save}, i.e. at startup. */
    public Optional<BanlistSnapshot> load() {
        AtomicFiles.deleteStaleTempFiles(file, TEMP_PREFIX);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.MAPPER.readValue(file.toFile(), BanlistSnapshot.class));
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring unreadable snapshot file {}", file, e);
            return Optional.empty();
        }
    }

    public void save(BanlistSnapshot snapshot) throws IOException {
        AtomicFiles.write(file, TEMP_PREFIX, out -> Json.MAPPER.writeValue(out, snapshot));
    }
}
