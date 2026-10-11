package at.magi.ygodiscordbot.impl.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates (and migrates) the tables of one repository once. {@link #ensure()} is called at start and before every
 * database use; while it keeps failing (database down) the next call tries again.
 */
public final class LazySchema {

    private static final Logger log = LoggerFactory.getLogger(LazySchema.class);

    /** The DDL and migration work, run on one connection. */
    @FunctionalInterface
    public interface SchemaSetup {
        void create(Connection connection) throws SQLException;
    }

    private final DataSource dataSource;
    private final String what;
    private final SchemaSetup setup;
    private volatile boolean ready;

    /** @param what what the schema is, for the log: "{what} is ready" */
    public LazySchema(DataSource dataSource, String what, SchemaSetup setup) {
        this.dataSource = dataSource;
        this.what = what;
        this.setup = setup;
    }

    /** A schema that is just these statements, in order. */
    public static SchemaSetup statements(String... ddl) {
        return connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : ddl) {
                    statement.execute(sql);
                }
            }
        };
    }

    /** Runs the setup unless it already succeeded. Retried on the next call if it failed. */
    public synchronized void ensure() throws SQLException {
        if (ready) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            setup.create(connection);
        }
        ready = true;
        log.info("{} is ready", what);
    }
}
