package at.magi.ygodiscordbot.utils.concurrent;

import org.testng.annotations.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class RefreshLoopTest {

    @Test
    public void runsScheduledTaskOnNamedDaemonThread() throws InterruptedException {
        RefreshLoop loop = new RefreshLoop("test-loop", "Test loop");
        AtomicReference<Thread> thread = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        loop.schedule(Duration.ZERO, () -> {
            thread.set(Thread.currentThread());
            ran.countDown();
        });
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        loop.close();
        assertEquals(thread.get().getName(), "test-loop");
        assertTrue(thread.get().isDaemon());
    }

    @Test
    public void taskCanRescheduleItself() throws InterruptedException {
        RefreshLoop loop = new RefreshLoop("test-loop", "Test loop");
        CountDownLatch twice = new CountDownLatch(2);
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            twice.countDown();
            loop.schedule(Duration.ZERO, task[0]);
        };
        loop.schedule(Duration.ZERO, task[0]);
        assertTrue(twice.await(5, TimeUnit.SECONDS));
        loop.close();
    }

    @Test
    public void closeInterruptsRunningTaskAndWaitsForIt() throws InterruptedException {
        RefreshLoop loop = new RefreshLoop("test-loop", "Test loop");
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        loop.schedule(Duration.ZERO, () -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            finished.set(true);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        loop.close();
        assertTrue(interrupted.get());
        assertTrue(finished.get(), "close() returns only after the task ended");
    }

    @Test
    public void closeGivesUpAfterTimeoutOnStuckTask() throws InterruptedException {
        RefreshLoop loop = new RefreshLoop("test-loop", "Test loop", Duration.ofMillis(100));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        loop.schedule(Duration.ZERO, () -> {
            started.countDown();
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException ignored) {
                    // ignores the interrupt on purpose
                }
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        long begin = System.nanoTime();
        loop.close();
        assertTrue(Duration.ofNanos(System.nanoTime() - begin).toSeconds() < 5);
        release.countDown();
    }

    @Test
    public void closeIsIdempotentAndCancelsPendingTasks() throws InterruptedException {
        RefreshLoop loop = new RefreshLoop("test-loop", "Test loop");
        AtomicBoolean ran = new AtomicBoolean();
        loop.schedule(Duration.ofMillis(200), () -> ran.set(true));
        loop.close();
        loop.close();
        Thread.sleep(400);
        assertFalse(ran.get());
    }

    @Test
    public void nothingIsScheduledAfterClose() throws InterruptedException {
        RefreshLoop loop = new RefreshLoop("test-loop", "Test loop");
        loop.close();
        AtomicBoolean ran = new AtomicBoolean();
        loop.schedule(Duration.ZERO, () -> ran.set(true));
        Thread.sleep(200);
        assertFalse(ran.get());
    }
}
