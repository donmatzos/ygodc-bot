package at.magi.ygodiscordbot.utils.discord;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public class MessageSenderTest {

    @Test
    public void genericDmFailure() {
        String reply = MessageSender.dmFailureMessage(new RuntimeException("boom"), "the leaderboard",
                "`/leaderboard page`");
        assertEquals(reply,
                "Something went wrong while sending the leaderboard. Please try again later.");
    }

    @Test
    public void otherErrorsAreNotAClosedDm() {
        assertFalse(MessageSender.isDmClosed(new RuntimeException("boom")));
        assertFalse(MessageSender.isDmClosed(null));
    }

    @Test
    public void sentReplyLinksTheDm() {
        assertEquals(MessageSender.sentReply("42", "the TCG list"),
                "📬 Sent the TCG list to your DMs: https://discord.com/channels/@me/42");
    }
}
