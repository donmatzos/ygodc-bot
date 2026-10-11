package at.magi.ygodiscordbot.utils.discord;

/** Checks shared by commands that post into a server channel on someone's behalf. */
public final class ChannelChecks {

    private ChannelChecks() {
    }

    /** Why the bot should not post in the channel for this member, or null if it can. */
    public static String channelProblem(boolean memberCanTalk, boolean botCanTalk, String channelMention) {
        if (!memberCanTalk) {
            return "❌ You can't send messages in " + channelMention + ", so I won't post there for you.";
        }
        if (!botCanTalk) {
            return "❌ I can't post in " + channelMention + ". Give me **View Channel** and **Send Messages** there.";
        }
        return null;
    }
}
