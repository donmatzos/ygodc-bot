package at.magi.ygodiscordbot.utils.discord;

import at.magi.ygodiscordbot.testutil.FakeInteractions;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static at.magi.ygodiscordbot.testutil.FakeInteractions.guild;
import static at.magi.ygodiscordbot.testutil.FakeInteractions.returns;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class MessageSenderTest {

    private static final List<String> MESSAGES = List.of("first", "second");

    private final List<String> sentCallbacks = new ArrayList<>();

    private void deliver(FakeInteractions fakes, Map<String, FakeInteractions.Answer> answers) {
        sentCallbacks.clear();
        MessageSender.deliver(fakes.event(answers), MESSAGES, "the TCG list", "`/banlist`",
                () -> sentCallbacks.add("sent"));
    }

    @Test
    public void inAServerTheMessagesGoToTheDmAndTheReplyLinksIt() {
        FakeInteractions fakes = new FakeInteractions();
        deliver(fakes, Map.of("getGuild", returns(guild()), "getUser", returns(fakes.dmUser(true))));
        assertEquals(fakes.sent(), List.of("dm:first", "dm:second",
                "edit:" + MessageSender.sentReply("42", "the TCG list")));
        assertEquals(sentCallbacks, List.of("sent"));
    }

    @Test
    public void closedDmsAreExplainedInTheReply() {
        FakeInteractions fakes = new FakeInteractions();
        deliver(fakes, Map.of("getGuild", returns(guild()), "getUser", returns(fakes.dmUser(false))));
        assertTrue(MessageSender.isDmClosed(FakeInteractions.dmClosed()));
        assertEquals(fakes.sent(), List.of("edit:" + MessageSender.dmFailureMessage(FakeInteractions.dmClosed(),
                "the TCG list", "`/banlist`")));
        assertTrue(fakes.sent().get(0).contains("I can't send you direct messages"), fakes.sent().get(0));
        assertTrue(sentCallbacks.isEmpty());
    }

    @Test
    public void inTheBotDmADeferredReplyIsReplacedWithPublicFollowUps() {
        FakeInteractions fakes = new FakeInteractions();
        deliver(fakes, Map.of("isAcknowledged", returns(true)));
        assertEquals(fakes.sent(), List.of("edit:first", "follow-up:second"));
        assertEquals(sentCallbacks, List.of("sent"));
    }

    @Test
    public void inTheBotDmWithoutDeferTheFirstMessageIsTheReply() {
        FakeInteractions fakes = new FakeInteractions();
        deliver(fakes, Map.of("isAcknowledged", returns(false)));
        assertEquals(fakes.sent(), List.of("reply:first", "follow-up:second"));
        assertEquals(sentCallbacks, List.of("sent"));
    }

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
