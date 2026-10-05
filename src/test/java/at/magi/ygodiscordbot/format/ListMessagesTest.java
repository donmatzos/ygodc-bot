package at.magi.ygodiscordbot.format;

import at.magi.ygodiscordbot.banlist.StaticBanlists;
import at.magi.ygodiscordbot.entity.BanStatus;
import at.magi.ygodiscordbot.entity.BanlistEntry;
import at.magi.ygodiscordbot.entity.GenesysPointEntry;
import at.magi.ygodiscordbot.entity.GenesysPointlist;
import at.magi.ygodiscordbot.entity.TcgBanlist;
import at.magi.ygodiscordbot.source.FixtureLists;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class ListMessagesTest {

    @Test
    public void tcgFitsDiscordLimitsAndContainsEveryCard() {
        TcgBanlist tcg = FixtureLists.tcg();
        List<String> messages = ListMessages.banlist("TCG Forbidden & Limited List", "Source: YGOProDeck", tcg);

        assertValidMessages(messages);
        String all = String.join("\n", messages);
        for (BanlistEntry entry : tcg.entries()) {
            assertTrue(all.contains("  " + entry.cardName() + "\n"), entry.cardName());
        }
        assertTrue(messages.get(0).startsWith("# TCG Forbidden & Limited List\n-# 222 cards"));
        assertTrue(all.contains("## 🔴 Forbidden · 121 cards"));
        assertTrue(all.contains("## 🟠 Limited · 93 cards"));
        assertTrue(all.contains("## 🟡 Semi-Limited · 8 cards"));
    }

    @Test
    public void genesysFitsDiscordLimitsAndContainsEveryCard() {
        GenesysPointlist genesys = FixtureLists.genesys();
        List<String> messages = ListMessages.genesys(genesys);

        assertValidMessages(messages);
        String all = String.join("\n", messages);
        for (GenesysPointEntry entry : genesys.entries()) {
            assertTrue(all.contains(String.format("%3d  %s%n", entry.points(), entry.cardName()).replace(System.lineSeparator(), "\n")),
                    entry.cardName());
        }
        assertTrue(all.contains("## 💯 100 points"));
    }

    @Test
    public void staticListsFitDiscordLimits() {
        assertValidMessages(ListMessages.banlist("Goat Format List", "April 2005 list", StaticBanlists.goat()));
        assertValidMessages(ListMessages.banlist("Edison Format List", "March 2010 list", StaticBanlists.edison()));
    }

    @Test
    public void continuedTablesRepeatTheirHeader() {
        List<BanlistEntry> entries = java.util.stream.IntStream.rangeClosed(1, 200)
                .mapToObj(i -> new BanlistEntry("Some Rather Long Card Name Number " + i, BanStatus.FORBIDDEN))
                .toList();
        List<String> messages = ListMessages.banlist("Test", "test", new TcgBanlist(Instant.EPOCH, entries));

        assertTrue(messages.size() > 1);
        for (String message : messages.subList(1, messages.size())) {
            assertTrue(message.startsWith("-# 🔴 Forbidden (continued)\n```\n  #  Card"), message);
        }
    }

    @Test
    public void backticksCannotBreakCodeBlocks() {
        TcgBanlist list = new TcgBanlist(Instant.EPOCH, List.of(new BanlistEntry("Evil```Card", BanStatus.LIMITED)));
        String message = ListMessages.banlist("Test", "test", list).get(0);

        assertFalse(message.contains("Evil```Card"));
        assertValidMessages(List.of(message));
    }

    private static void assertValidMessages(List<String> messages) {
        assertFalse(messages.isEmpty());
        for (String message : messages) {
            assertTrue(message.length() <= MessagePacker.MAX_MESSAGE_LENGTH, "too long: " + message.length());
            assertEquals(message.split("```", -1).length % 2, 1, "unbalanced code fences in:\n" + message);
        }
    }
}
