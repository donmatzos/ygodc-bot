package at.magi.ygodiscordbot.testutil;

import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;

import java.util.List;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Assertions for texts that are going to be sent to Discord. */
public final class DiscordAssert {

    private DiscordAssert() {
    }

    /** Messages must exist, be non-empty, fit Discord's length limit and not leave a code fence open. */
    public static void assertSendable(List<String> messages) {
        assertFalse(messages.isEmpty(), "no messages");
        for (String message : messages) {
            assertFalse(message.isEmpty(), "empty message");
            assertTrue(message.length() <= DcMessageUtils.MAX_MESSAGE_LENGTH, "too long: " + message.length());
            assertTrue(message.split("```", -1).length % 2 == 1, "unbalanced code fences in:\n" + message);
        }
    }
}
