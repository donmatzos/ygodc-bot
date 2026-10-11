package at.magi.ygodiscordbot;

import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class YgoDiscordBotTest {

    @Test
    public void aStepThatWasInterruptedDoesNotCutTheNextStepShort() {
        boolean[] nextSawInterrupt = {true};
        Deque<Runnable> steps = new ArrayDeque<>();
        steps.add(() -> Thread.currentThread().interrupt());
        steps.add(() -> nextSawInterrupt[0] = Thread.currentThread().isInterrupted());
        try {
            YgoDiscordBot.runSteps(steps);
            assertFalse(nextSawInterrupt[0], "the interrupt flag must be cleared before the next step");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is restored afterwards");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void allStepsRunEvenIfOneFails() {
        int[] ran = {0};
        Deque<Runnable> steps = new ArrayDeque<>();
        steps.add(() -> { throw new IllegalStateException("boom"); });
        steps.add(() -> ran[0]++);
        YgoDiscordBot.runSteps(steps);
        assertEquals(ran[0], 1);
    }
}
