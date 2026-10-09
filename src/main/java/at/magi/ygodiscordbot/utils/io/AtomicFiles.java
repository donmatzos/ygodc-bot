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

/**
 * Writes files via a temporary file that is then renamed, so a crash mid-write never leaves a broken file.
 * A process killed mid-write can leave the temporary file behind; {@link #deleteStaleTempFiles} removes those.
 */
public final class AtomicFiles {

    private static final Logger log = LoggerFactory.getLogger(AtomicFiles.class);

    private static final String TEMP_SUFFIX = ".tmp";

    @FunctionalInterface
    public interface Content {
        void writeTo(OutputStream out) throws IOException;
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

    /** Call before the first {@link #write} for that prefix, i.e. at startup. */
    public static void deleteStaleTempFiles(Path file, String tempPrefix) {
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
}
