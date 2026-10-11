package at.magi.ygodiscordbot.utils.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Writes files via a temporary file that is then renamed, so a crash mid-write never leaves a broken file.
 * A process killed mid-write can leave the temporary file behind; {@link #readIfPresent} removes those.
 */
public final class AtomicFiles {

    private static final Logger log = LoggerFactory.getLogger(AtomicFiles.class);

    private static final String TEMP_SUFFIX = ".tmp";

    @FunctionalInterface
    public interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    @FunctionalInterface
    public interface Reader<T> {
        T readFrom(Path file) throws IOException;
    }

    private AtomicFiles() {
    }

    /** Temporary files are named {@code <tempPrefix>*.tmp} next to {@code file}. */
    public static void write(Path file, String tempPrefix, Content content) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, tempPrefix, TEMP_SUFFIX);
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                content.writeTo(out);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Removes temp files a killed process left behind; done by {@link #readIfPresent} at startup. */
    private static void deleteStaleTempFiles(Path file, String tempPrefix) {
        Path directory = file.toAbsolutePath().getParent();
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> temps = Files.newDirectoryStream(directory, tempPrefix + "*" + TEMP_SUFFIX)) {
            for (Path temp : temps) {
                Files.deleteIfExists(temp);
                log.info("Deleted unfinished file {}", temp);
            }
        } catch (IOException e) {
            log.warn("Could not delete unfinished files in {}", directory, e);
        }
    }

    /**
     * Startup read of a file written by {@link #write}: deletes stale temporary files first, then reads the file.
     * Empty if the file is missing, or if {@code reader} fails (logged as a warning).
     */
    public static <T> Optional<T> readIfPresent(Path file, String tempPrefix, Reader<T> reader) {
        deleteStaleTempFiles(file, tempPrefix);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(reader.readFrom(file));
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring unreadable file {}", file, e);
            return Optional.empty();
        }
    }
}
