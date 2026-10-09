package at.magi.ygodiscordbot.utils.discord;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class MessageSenderTest {

    @Test
    public void genericDmFailure() {
        assertEquals(MessageSender.dmFailureMessage(new RuntimeException("boom"), "`/banlist`"),
                "Something went wrong while sending the list. Please try again later.");
    }
}
