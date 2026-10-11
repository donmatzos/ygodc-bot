package at.magi.ygodiscordbot.impl.database;

import org.testng.annotations.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

public class LazySchemaTest {

    /** A pool that fails the first {@code failures} connection requests, then hands out inert connections. */
    private static DataSource pool(int failures) {
        AtomicInteger requests = new AtomicInteger();
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (requests.incrementAndGet() <= failures) {
                        throw new SQLException("down");
                    }
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (p, m, a) -> null);
                });
    }

    @Test
    public void statementsRunInOrderOnOneStatement() throws SQLException {
        List<String> executed = new ArrayList<>();
        Statement statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("execute")) {
                        executed.add((String) args[0]);
                        return false;
                    }
                    if (method.getName().equals("close")) {
                        executed.add("closed");
                    }
                    return null;
                });
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> method.getName().equals("createStatement") ? statement : null);

        LazySchema.statements("CREATE TABLE a", "CREATE TABLE b").create(connection);

        assertEquals(executed, List.of("CREATE TABLE a", "CREATE TABLE b", "closed"));
    }

    @Test
    public void runsSetupOnceAfterSuccess() throws SQLException {
        DataSource dataSource = pool(0);
        AtomicInteger runs = new AtomicInteger();
        LazySchema schema = new LazySchema(dataSource, "Test table", connection -> runs.incrementAndGet());

        schema.ensure();
        schema.ensure();

        assertEquals(runs.get(), 1);
    }

    @Test
    public void retriesOnTheNextCallAfterAFailure() throws SQLException {
        DataSource dataSource = pool(1);
        AtomicInteger runs = new AtomicInteger();
        LazySchema schema = new LazySchema(dataSource, "Test table", connection -> runs.incrementAndGet());

        expectThrows(SQLException.class, schema::ensure);
        assertEquals(runs.get(), 0);
        schema.ensure();
        schema.ensure();

        assertEquals(runs.get(), 1);
    }

    @Test
    public void retriesWhenTheSetupFails() throws SQLException {
        DataSource dataSource = pool(0);
        AtomicInteger runs = new AtomicInteger();
        LazySchema schema = new LazySchema(dataSource, "Test table", connection -> {
            if (runs.incrementAndGet() == 1) {
                throw new SQLException("DDL failed");
            }
        });

        expectThrows(SQLException.class, schema::ensure);
        schema.ensure();

        assertEquals(runs.get(), 2);
    }
}
