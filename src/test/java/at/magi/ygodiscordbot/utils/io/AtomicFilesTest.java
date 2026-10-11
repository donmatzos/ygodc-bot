package at.magi.ygodiscordbot.utils.io;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class AtomicFilesTest {

    private Path dir;
    private Path file;

    @BeforeMethod
    public void createTempDir() throws IOException {
        dir = Files.createTempDirectory("atomic-files-test");
        file = dir.resolve("data.txt");
    }

    @AfterMethod(alwaysRun = true)
    public void deleteTempDir() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static String readText(Path path) throws IOException {
        return Files.readString(path);
    }

    @Test
    public void readIfPresentReturnsTheReadValue() throws IOException {
        AtomicFiles.write(file, "data", out -> out.write("hello".getBytes()));
        assertEquals(AtomicFiles.readIfPresent(file, "data", AtomicFilesTest::readText), Optional.of("hello"));
    }

    @Test
    public void readIfPresentOfAMissingFileIsEmptyWithoutCallingTheReader() {
        Optional<String> result = AtomicFiles.readIfPresent(file, "data", path -> {
            throw new AssertionError("must not be called");
        });
        assertEquals(result, Optional.empty());
    }

    @Test
    public void readIfPresentDeletesStaleTempFilesOfThePrefixOnly() throws IOException {
        Path stale = Files.createFile(dir.resolve("data123.tmp"));
        Path other = Files.createFile(dir.resolve("other123.tmp"));
        AtomicFiles.readIfPresent(file, "data", AtomicFilesTest::readText);
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(other));
    }

    @Test
    public void readIfPresentOfACorruptFileIsEmpty() throws IOException {
        Files.writeString(file, "broken");
        assertEquals(AtomicFiles.readIfPresent(file, "data", path -> {
            throw new IOException("corrupt");
        }), Optional.empty());
        assertEquals(AtomicFiles.readIfPresent(file, "data", path -> {
            throw new IllegalArgumentException("corrupt");
        }), Optional.empty());
    }
}
