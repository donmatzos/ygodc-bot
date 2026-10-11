package at.magi.ygodiscordbot.impl.database;

import org.testng.annotations.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
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
