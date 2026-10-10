package at.magi.ygodiscordbot.impl.config;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Runtime configuration. Values come from environment variables or, when those are not set,
 * from {@value #CONFIG_FILE} in the working directory (hosting panels like Pterodactyl
 * usually offer no way to set custom environment variables).
 *
 * @param token      DISCORD_TOKEN, the bot token from the Discord developer portal
 * @param devGuildId DEV_GUILD_ID (optional), registers commands on one server only for instant updates
 * @param database   DB_URL, DB_USER, DB_PASSWORD (optional), the MySQL database for decklists;
 *                   null if DB_URL is not set
 */
public record BotConfig(String token, String devGuildId, DatabaseConfig database) {

    /** Keeps the token out of logs. */
    @Override
    public String toString() {
        return "BotConfig[token=***, devGuildId=" + devGuildId + ", database=" + database + "]";
    }

    public static final String CONFIG_FILE = "bot.properties";

    public static BotConfig load() {
        Map<String, String> values = new HashMap<>(readFile(Path.of(CONFIG_FILE)));
        System.getenv().forEach((key, value) -> {
            if (!value.isBlank()) {
                values.put(key, value);
            }
        });
        return from(values);
    }

    static BotConfig from(Map<String, String> values) {
        String token = values.get("DISCORD_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "DISCORD_TOKEN is not set (use an environment variable or " + CONFIG_FILE + ")");
        }
        String url = optional(values, "DB_URL");
        DatabaseConfig database = url == null ? null
                : new DatabaseConfig(url, optional(values, "DB_USER"), optional(values, "DB_PASSWORD"));
        return new BotConfig(token.strip(), optional(values, "DEV_GUILD_ID"), database);
    }

    private static String optional(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null || value.isBlank() ? null : value.strip();
    }

    static Map<String, String> readFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
        Map<String, String> values = new HashMap<>();
        properties.stringPropertyNames().forEach(name -> values.put(name, properties.getProperty(name)));
        return values;
    }
}
