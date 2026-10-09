package at.magi.ygodiscordbot.impl.runtime;

import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class RestartPolicyTest {

    private static final Duration SHORT = Duration.ofSeconds(30);

    @Test
    public void cleanExitIsNotRestarted() {
        assertTrue(new RestartPolicy().afterExit(0, SHORT).isEmpty());
    }

    @Test
    public void configErrorIsNotRestarted() {
        assertTrue(new RestartPolicy().afterExit(Supervisor.EXIT_CONFIG_ERROR, SHORT).isEmpty());
    }

    @Test
    public void outOfMemoryIsRestarted() {
        assertEquals(new RestartPolicy().afterExit(Supervisor.EXIT_OUT_OF_MEMORY, SHORT), Optional.of(Duration.ofSeconds(5)));
    }

    @Test
    public void repeatedCrashesBackOffUpToMaximum() {
        RestartPolicy policy = new RestartPolicy();

        assertEquals(policy.afterExit(1, SHORT).orElseThrow(), Duration.ofSeconds(5));
        assertEquals(policy.afterExit(1, SHORT).orElseThrow(), Duration.ofSeconds(10));
        assertEquals(policy.afterExit(1, SHORT).orElseThrow(), Duration.ofSeconds(20));
        for (int i = 0; i < 100; i++) {
            policy.afterExit(1, SHORT);
        }
        assertEquals(policy.afterExit(1, SHORT).orElseThrow(), RestartPolicy.MAX_DELAY);
    }

    @Test
    public void stableRunResetsBackOff() {
        RestartPolicy policy = new RestartPolicy();
        policy.afterExit(1, SHORT);
        policy.afterExit(1, SHORT);

        assertEquals(policy.afterExit(1, RestartPolicy.STABLE_UPTIME).orElseThrow(), Duration.ofSeconds(5));
    }
}
