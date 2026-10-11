package at.magi.ygodiscordbot.impl.database;

import org.testng.annotations.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.expectThrows;

/** Uses proxy connections that only record the transaction calls, so no database is needed. */
public class TransactionsTest {

    /** A pool whose connections record commit / rollback / setAutoCommit / close; commit and rollback can fail. */
    private static DataSource recording(List<String> calls, boolean commitFails, boolean rollbackFails) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (p, m, a) -> {
                                switch (m.getName()) {
                                    case "commit" -> {
                                        calls.add("commit");
                                        if (commitFails) {
                                            throw new SQLException("commit failed");
                                        }
                                    }
                                    case "rollback" -> {
                                        calls.add("rollback");
                                        if (rollbackFails) {
                                            throw new SQLException("rollback failed");
                                        }
                                    }
                                    case "setAutoCommit" -> calls.add("setAutoCommit(" + a[0] + ")");
                                    case "close" -> calls.add("close");
                                    default -> throw new UnsupportedOperationException(m.getName());
                                }
                                return null;
                            });
                });
    }

    @Test
    public void commitsAndReturnsTheResult() throws SQLException {
        List<String> calls = new ArrayList<>();
        String result = Transactions.inTransaction(recording(calls, false, false), connection -> {
            calls.add("work");
            return "done";
        });
        assertEquals(result, "done");
        assertEquals(calls, List.of("setAutoCommit(false)", "work", "commit", "setAutoCommit(true)", "close"));
    }

    @Test
    public void failedWorkRollsBackAndRethrowsTheSameException() {
        List<String> calls = new ArrayList<>();
        SQLException failure = new SQLException("work failed");
        SQLException error = expectThrows(SQLException.class, () ->
                Transactions.inTransaction(recording(calls, false, false), connection -> {
                    throw failure;
                }));
        assertSame(error, failure);
        assertEquals(calls, List.of("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close"));
    }

    @Test
    public void runtimeExceptionRollsBack() {
        List<String> calls = new ArrayList<>();
        expectThrows(IllegalStateException.class, () ->
                Transactions.inTransaction(recording(calls, false, false), connection -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals(calls, List.of("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close"));
    }

    @Test
    public void failedCommitRollsBack() {
        List<String> calls = new ArrayList<>();
        SQLException error = expectThrows(SQLException.class, () ->
                Transactions.inTransaction(recording(calls, true, false), connection -> "x"));
        assertEquals(error.getMessage(), "commit failed");
        assertEquals(calls, List.of("setAutoCommit(false)", "commit", "rollback", "setAutoCommit(true)", "close"));
    }

    /** setAutoCommit(true) would commit a transaction that could not be rolled back. */
    @Test
    public void failedRollbackKeepsTheOriginalErrorAndLeavesAutoCommitOff() {
        List<String> calls = new ArrayList<>();
        SQLException error = expectThrows(SQLException.class, () ->
                Transactions.inTransaction(recording(calls, true, true), connection -> "x"));
        assertEquals(error.getMessage(), "commit failed");
        assertEquals(error.getSuppressed().length, 1);
        assertEquals(error.getSuppressed()[0].getMessage(), "rollback failed");
        assertEquals(calls, List.of("setAutoCommit(false)", "commit", "rollback", "close"));
    }
}
