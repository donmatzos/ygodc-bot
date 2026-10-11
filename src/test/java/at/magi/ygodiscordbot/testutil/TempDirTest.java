package at.magi.ygodiscordbot.testutil;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Base for tests that need an empty directory of their own, deleted again after each test. */
public abstract class TempDirTest {

    protected Path dir;

    @BeforeMethod
    public void createTempDir() throws IOException {
        dir = Files.createTempDirectory("ygo-bot-test");
    }

    @AfterMethod(alwaysRun = true)
    public void deleteTempDir() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** The sorted names of the files directly in the directory. */
    protected List<String> fileNames() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
