package at.magi.ygodiscordbot.impl.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Runs JDBC work in one transaction on a pooled connection. */
public final class Transactions {

    private static final Logger log = LoggerFactory.getLogger(Transactions.class);

    private Transactions() {
    }

    /** Database work on the connection of a transaction. */
    @FunctionalInterface
    public interface TransactionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code work} with auto-commit off and commits afterwards. If the work or the commit fails, the
     * transaction is rolled back and the original error is rethrown (a failing rollback is added as suppressed).
     */
    public static <T> T inTransaction(DataSource dataSource, TransactionWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            boolean ended = false;
            try {
                T result = work.run(connection);
                connection.commit();
                ended = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                    ended = true;
                } catch (SQLException rollbackError) {
                    // The original error is the one worth reporting
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                // setAutoCommit(true) would COMMIT a transaction that failed to roll back; the pool rolls it back
                // (or drops the connection) when it is returned instead
                if (ended) {
                    restoreAutoCommit(connection);
                }
            }
        }
    }

    /** Never throws: after a commit the change is done, and the pool resets auto-commit on return anyway. */
    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("Could not restore auto-commit, the connection pool resets it", e);
        }
    }
}
