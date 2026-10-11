package at.magi.ygodiscordbot.impl.database;

import org.testng.annotations.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/** Uses proxies that record the JDBC calls, so no database is needed. */
public class JdbcTest {

    private static Connection recording(List<String> calls, int rows) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> {
                    if (m.getName().equals("prepareStatement")) {
                        calls.add("sql: " + a[0]);
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                                    switch (sm.getName()) {
                                        case "setObject" -> calls.add(sa[0] + "=" + sa[1]);
                                        case "setNull" -> calls.add(sa[0] + "=NULL(" + sa[1] + ")");
                                        case "executeUpdate" -> {
                                            return rows;
                                        }
                                        case "close" -> calls.add("close statement");
                                        default -> throw new UnsupportedOperationException(sm.getName());
                                    }
                                    return null;
                                });
                    }
                    if (m.getName().equals("close")) {
                        calls.add("close connection");
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    public void updateBindsParametersInOrderAndNullAsNull() throws SQLException {
        List<String> calls = new ArrayList<>();
        int rows = Jdbc.update(recording(calls, 3), "UPDATE t SET a = ?, b = ?, c = ?", 5L, null, "x");
        assertEquals(rows, 3);
        assertEquals(calls, List.of("sql: UPDATE t SET a = ?, b = ?, c = ?", "1=5", "2=NULL(" + Types.BIGINT + ")",
                "3=x", "close statement"));
    }

    @Test
    public void updateOnDataSourceReturnsTheConnection() throws SQLException {
        List<String> calls = new ArrayList<>();
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, a) -> recording(calls, 1));
        assertEquals(Jdbc.update(dataSource, "DELETE FROM t WHERE id = ?", 9), 1);
        assertEquals(calls, List.of("sql: DELETE FROM t WHERE id = ?", "1=9", "close statement", "close connection"));
    }

    private static ResultSet row(long value, boolean wasNull) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getLong" -> value;
                    case "wasNull" -> wasNull;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    public void nullableLongReadsValuesAndNulls() throws SQLException {
        assertEquals(Jdbc.nullableLong(row(42, false), 1), Long.valueOf(42));
        assertEquals(Jdbc.nullableLong(row(0, false), 1), Long.valueOf(0));
        assertNull(Jdbc.nullableLong(row(0, true), 1));
    }
}
