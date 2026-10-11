package at.magi.ygodiscordbot.impl.runtime;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Optional;

import static org.testng.Assert.assertEquals;

public class RestartPolicyTest {

    private static final Duration SHORT = Duration.ofSeconds(30);

    @DataProvider
    public static Object[][] exitCodes() {
        return new Object[][]{
                {"clean exit", 0, Optional.empty()},
                {"config error", Supervisor.EXIT_CONFIG_ERROR, Optional.empty()},
                {"out of memory", Supervisor.EXIT_OUT_OF_MEMORY, Optional.of(Duration.ofSeconds(5))},
                {"crash", 1, Optional.of(Duration.ofSeconds(5))}};
    }

    @Test(dataProvider = "exitCodes")
    public void restartDependsOnTheExitCode(String name, int exitCode, Optional<Duration> expected) {
        assertEquals(new RestartPolicy().afterExit(exitCode, SHORT), expected, name);
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
