package at.magi.ygodiscordbot.utils.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class FetchingTest {

    private static final Logger LOG = LoggerFactory.getLogger(FetchingTest.class);

    @Test
    public void returnsFetchedValue() {
        assertEquals(Fetching.orNull(LOG, "x", () -> "value"), "value");
    }

    @Test
    public void ioAndRuntimeFailuresGiveNull() {
        assertNull(Fetching.<String>orNull(LOG, "x", () -> {
            throw new IOException("down");
        }));
        assertNull(Fetching.<String>orNull(LOG, "x", () -> {
            throw new IllegalStateException("bad");
        }));
    }

    @Test
    public void interruptDuringFetchGivesNullAndKeepsInterruptFlag() {
        try {
            assertNull(Fetching.<String>orNull(LOG, "x", () -> {
                throw new InterruptedException();
            }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void interruptedThreadDoesNotFetch() {
        Thread.currentThread().interrupt();
        try {
            assertNull(Fetching.<String>orNull(LOG, "x", () -> {
                throw new AssertionError("must not fetch");
            }));
        } finally {
            Thread.interrupted();
        }
    }
}
