package at.magi.ygodiscordbot.banlist;

import at.magi.ygodiscordbot.entity.BanStatus;
import at.magi.ygodiscordbot.entity.EdisonBanlist;
import at.magi.ygodiscordbot.entity.GoatBanlist;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class StaticBanlistsTest {

    @Test
    public void loadsGoat() {
        GoatBanlist goat = StaticBanlists.goat();
        assertEquals(goat.name(), "April 2005");
        assertEquals(goat.entries().size(), 73);
        assertEquals(goat.withStatus(BanStatus.FORBIDDEN).size(), 17);
        assertEquals(goat.withStatus(BanStatus.LIMITED).size(), 41);
        assertEquals(goat.withStatus(BanStatus.SEMI_LIMITED).size(), 15);
    }

    @Test
    public void loadsEdison() {
        EdisonBanlist edison = StaticBanlists.edison();
        assertEquals(edison.name(), "March 2010");
        assertEquals(edison.entries().size(), 132);
        assertEquals(edison.withStatus(BanStatus.FORBIDDEN).size(), 43);
        assertEquals(edison.withStatus(BanStatus.LIMITED).size(), 70);
        assertEquals(edison.withStatus(BanStatus.SEMI_LIMITED).size(), 19);
    }
}
