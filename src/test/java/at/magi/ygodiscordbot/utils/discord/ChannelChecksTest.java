package at.magi.ygodiscordbot.utils.discord;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ChannelChecksTest {

    @DataProvider
    public Object[][] channels() {
        return new Object[][]{
                {"both can talk", true, true, List.of()},
                {"member can't post", false, true, List.of("You can't send messages in #results")},
                {"bot can't post", true, false, List.of("View Channel", "Send Messages")},
        };
    }

    /** An empty list of expected fragments means no problem. */
    @Test(dataProvider = "channels")
    public void channelProblem(String name, boolean memberCanTalk, boolean botCanTalk, List<String> fragments) {
        String problem = ChannelChecks.channelProblem(memberCanTalk, botCanTalk, "#results");
        if (fragments.isEmpty()) {
            assertNull(problem, name);
        }
        for (String fragment : fragments) {
            assertTrue(problem != null && problem.contains(fragment), name + ": " + problem);
        }
    }
}
