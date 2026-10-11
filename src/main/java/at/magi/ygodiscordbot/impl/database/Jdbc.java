package at.magi.ygodiscordbot.impl.database;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Small helpers for repetitive JDBC code. */
public final class Jdbc {

    private Jdbc() {
    }

    /**
     * Runs an INSERT/UPDATE/DELETE with the parameters bound in order; a {@code null} is bound as a SQL NULL typed BIGINT.
     * Returns the row count the driver reports.
     */
    public static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i] == null) {
                    statement.setNull(i + 1, Types.BIGINT);
                } else {
                    statement.setObject(i + 1, parameters[i]);
                }
            }
            return statement.executeUpdate();
        }
    }

    /** Like {@link #update(Connection, String, Object...)} on a connection borrowed from the pool. */
    public static int update(DataSource dataSource, String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return update(connection, sql, parameters);
        }
    }

    /** A BIGINT column that may be NULL: null if it is. */
    public static Long nullableLong(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
