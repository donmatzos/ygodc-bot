package at.magi.ygodiscordbot.impl.banlist;

import at.magi.ygodiscordbot.entity.banlist.BanStatus;
import at.magi.ygodiscordbot.entity.banlist.BanlistEntry;
import at.magi.ygodiscordbot.entity.banlist.GenesysPointEntry;
import at.magi.ygodiscordbot.entity.banlist.GenesysPointlist;
import at.magi.ygodiscordbot.entity.banlist.TcgBanlist;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;

import static at.magi.ygodiscordbot.testutil.DiscordAssert.assertSendable;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class ListMessagesTest {

    @Test
    public void tcgFitsDiscordLimitsAndContainsEveryCard() {
        TcgBanlist tcg = FixtureLists.tcg();
        List<String> messages = ListMessages.banlist("TCG Forbidden & Limited List", "Source: YGOProDeck", tcg);

        assertSendable(messages);
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

        assertSendable(messages);
        String all = String.join("\n", messages);
        for (GenesysPointEntry entry : genesys.entries()) {
            assertTrue(all.contains(String.format("%3d  %s%n", entry.points(), entry.cardName()).replace(System.lineSeparator(), "\n")),
                    entry.cardName());
        }
        assertTrue(all.contains("## 💯 100 points"));
    }

    @Test
    public void staticListsFitDiscordLimits() {
        assertSendable(ListMessages.banlist("Goat Format List", "April 2005 list", StaticBanlists.goat()));
        assertSendable(ListMessages.banlist("Edison Format List", "March 2010 list", StaticBanlists.edison()));
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
        assertSendable(List.of(message));
    }
}
