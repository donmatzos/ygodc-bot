package at.magi.ygodiscordbot.utils.discord;

import org.testng.annotations.Test;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ChannelChecksTest {

    @Test
    public void allowedWhenBothCanTalk() {
        assertNull(ChannelChecks.channelProblem(true, true, "#results"));
    }

    @Test
    public void memberMustBeAbleToPostThere() {
        String problem = ChannelChecks.channelProblem(false, true, "#results");
        assertTrue(problem.contains("You can't send messages in #results"), problem);
    }

    @Test
    public void botMustBeAbleToPostThere() {
        String problem = ChannelChecks.channelProblem(true, false, "#results");
        assertTrue(problem.contains("View Channel"), problem);
        assertTrue(problem.contains("Send Messages"), problem);
    }
}
