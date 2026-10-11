package at.magi.ygodiscordbot.impl.config;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class BotConfigTest {

    private Path dir;

    @BeforeMethod
    public void createTempDir() throws IOException {
        dir = Files.createTempDirectory("bot-config-test");
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
    public void readsTokenAndGuild() {
        BotConfig config = BotConfig.from(Map.of("DISCORD_TOKEN", " abc ", "DEV_GUILD_ID", "123"));
        assertEquals(config.token(), "abc");
        assertEquals(config.devGuildId(), "123");
    }

    @Test
    public void guildIsOptional() {
        assertNull(BotConfig.from(Map.of("DISCORD_TOKEN", "abc")).devGuildId());
    }

    @Test
    public void missingTokenFails() {
        assertThrows(IllegalStateException.class, () -> BotConfig.from(Map.of()));
    }

    @Test
    public void databaseIsOptional() {
        assertNull(BotConfig.from(Map.of("DISCORD_TOKEN", "abc", "DB_URL", " ")).database());
    }

    @Test
    public void readsDatabase() {
        DatabaseConfig database = BotConfig.from(Map.of("DISCORD_TOKEN", "abc",
                "DB_URL", "jdbc:mysql://host:3306/db", "DB_USER", "u", "DB_PASSWORD", "secret")).database();
        assertEquals(database.url(), "jdbc:mysql://host:3306/db");
        assertEquals(database.user(), "u");
        assertEquals(database.password(), "secret");
        assertFalse(database.toString().contains("secret"));
    }

    @Test
    public void safeUrlHidesCredentials() {
        DatabaseConfig database = new DatabaseConfig("jdbc:mysql://u:secret@host:3306/db", null, null);
        assertEquals(database.safeUrl(), "jdbc:mysql://***@host:3306/db");
    }

    @Test
    public void readsPropertiesFile() throws IOException {
        Path file = dir.resolve(BotConfig.CONFIG_FILE);
        Files.writeString(file, """
                # comment
                DISCORD_TOKEN=abc
                DEV_GUILD_ID=
                """);
        Map<String, String> values = BotConfig.readFile(file);
        assertEquals(values.get("DISCORD_TOKEN"), "abc");
        assertNull(BotConfig.from(values).devGuildId());
    }

    @Test
    public void missingFileIsEmpty() {
        assertTrue(BotConfig.readFile(dir.resolve("absent.properties")).isEmpty());
    }

    @Test
    public void toStringDoesNotContainToken() {
        BotConfig config = BotConfig.from(Map.of("DISCORD_TOKEN", "fake-token-value", "DEV_GUILD_ID", "123"));
        assertFalse(config.toString().contains("fake-token-value"), config.toString());
        assertTrue(config.toString().contains("123"));
    }
}
