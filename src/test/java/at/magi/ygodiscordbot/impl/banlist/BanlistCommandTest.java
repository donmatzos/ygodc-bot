package at.magi.ygodiscordbot.impl.banlist;

import at.magi.ygodiscordbot.testutil.FakeInteractions;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static at.magi.ygodiscordbot.testutil.FakeInteractions.guild;
import static at.magi.ygodiscordbot.testutil.FakeInteractions.returns;
import static at.magi.ygodiscordbot.testutil.FakeInteractions.stringOption;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class BanlistCommandTest {

    private static final List<?> GOAT = List.of(stringOption("format", "goat", Map.of()));

    @Test
    public void inAServerTheListIsSentToTheDmOnlyAfterTheDeferWasAccepted() {
        FakeInteractions fakes = new FakeInteractions();
        new BanlistCommand(TestLists.repository()).execute(fakes.event(Map.of("getOptions", returns(GOAT),
                "getGuild", returns(guild()), "getUser", returns(fakes.dmUser(true)))));
        List<String> sent = fakes.sent();
        assertEquals(sent.get(0), "reply (ephemeral)");
        assertTrue(sent.get(1).startsWith("dm:"), sent.toString());
        assertEquals(sent.get(sent.size() - 1), "edit:📬 Sent the Goat list to your DMs: "
                + "https://discord.com/channels/@me/42");
    }

    @Test
    public void rejectedDeferSendsNothing() {
        FakeInteractions fakes = new FakeInteractions();
        new BanlistCommand(TestLists.repository()).execute(fakes.event(Map.of("getOptions", returns(GOAT),
                "getGuild", returns(guild()), "getUser", returns(fakes.dmUser(true)),
                "deferReply", (m, a) -> FakeInteractions.failing(ReplyCallbackAction.class,
                        new IllegalStateException("Unknown interaction")))));
        assertTrue(fakes.sent().isEmpty(), fakes.sent().toString());
    }
}
