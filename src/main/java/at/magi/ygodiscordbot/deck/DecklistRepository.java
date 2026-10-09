package at.magi.ygodiscordbot.deck;

import at.magi.ygodiscordbot.entity.deck.Decklist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decklists per Discord user, in plain JDBC. Calls block on the database, so callers run them off the
 * JDA event thread, and on a single thread (the per-user limit check and insert are not atomic).
 * Name lookups ignore case (database collation).
 */
public class DecklistRepository {

    private static final Logger log = LoggerFactory.getLogger(DecklistRepository.class);

    /** Keeps the free database small and stops a single user from filling it. */
    public static final int MAX_DECKS_PER_USER = 50;

    /** MySQL and MariaDB error code for a duplicate key (ER_DUP_ENTRY). */
    static final int DUPLICATE_KEY = 1062;

    public enum SaveResult { SAVED, NAME_TAKEN, LIMIT_REACHED }

    private final DataSource dataSource;
    private final Clock clock;
    private volatile boolean schemaReady;

    public DecklistRepository(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** Creates the table if it does not exist yet. Retried on the next call if the database is down. */
    public synchronized void ensureSchema() throws SQLException {
        if (schemaReady) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(DeckDatabase.SCHEMA);
        }
        schemaReady = true;
        log.info("Decklist table is ready");
    }

    public SaveResult create(long userId, String name, String ydke) throws SQLException {
        ensureSchema();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM decklist WHERE user_id = ?")) {
                count.setLong(1, userId);
                try (ResultSet result = count.executeQuery()) {
                    result.next();
                    if (result.getLong(1) >= MAX_DECKS_PER_USER) {
                        return SaveResult.LIMIT_REACHED;
                    }
                }
            }
            long now = clock.millis();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO decklist (user_id, name, ydke, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                insert.setLong(1, userId);
                insert.setString(2, name);
                insert.setString(3, ydke);
                insert.setLong(4, now);
                insert.setLong(5, now);
                insert.executeUpdate();
                return SaveResult.SAVED;
            } catch (SQLException e) {
                // The unique key (user_id, name) decides, so no separate lookup is needed
                if (e.getErrorCode() == DUPLICATE_KEY) {
                    return SaveResult.NAME_TAKEN;
                }
                throw e;
            }
        }
    }

    public Optional<Decklist> find(long userId, String name) throws SQLException {
        ensureSchema();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT id, user_id, name, ydke, created_at, updated_at FROM decklist "
                             + "WHERE user_id = ? AND name = ?")) {
            select.setLong(1, userId);
            select.setString(2, name);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Decklist(result.getLong("id"), result.getLong("user_id"),
                        result.getString("name"), result.getString("ydke"),
                        result.getLong("created_at"), result.getLong("updated_at")));
            }
        }
    }

    /** Returns the deck as stored after the update, or empty if the user has no deck with that name. */
    public Optional<Decklist> update(long userId, String name, String ydke) throws SQLException {
        ensureSchema();
        // Connector/J reports matched rows by default, so an unchanged deck still counts as found
        int updated = execute("UPDATE decklist SET ydke = ?, updated_at = ? WHERE user_id = ? AND name = ?",
                ydke, clock.millis(), userId, name);
        return updated > 0 ? find(userId, name) : Optional.empty();
    }

    /** Returns the deleted deck, or empty if the user has no deck with that name. */
    public Optional<Decklist> delete(long userId, String name) throws SQLException {
        Optional<Decklist> deck = find(userId, name);
        if (deck.isPresent()) {
            execute("DELETE FROM decklist WHERE id = ?", deck.get().id());
        }
        return deck;
    }

    /** Names of the user's decks, alphabetically. */
    public List<String> names(long userId) throws SQLException {
        ensureSchema();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT name FROM decklist WHERE user_id = ? ORDER BY name")) {
            select.setLong(1, userId);
            try (ResultSet result = select.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (result.next()) {
                    names.add(result.getString(1));
                }
                return names;
            }
        }
    }

    private int execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }
}
