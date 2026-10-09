package at.magi.ygodiscordbot.utils.discord;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class MessageSenderTest {

    @Test
    public void genericDmFailure() {
        String reply = MessageSender.dmFailureMessage(new RuntimeException("boom"), "the leaderboard",
                "`/leaderboard page`");
        assertEquals(reply,
                "Something went wrong while sending the leaderboard. Please try again later.");
    }
}
