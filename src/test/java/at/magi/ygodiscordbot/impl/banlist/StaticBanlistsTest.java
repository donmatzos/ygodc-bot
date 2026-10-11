package at.magi.ygodiscordbot.impl.banlist;

import at.magi.ygodiscordbot.entity.banlist.BanStatus;
import at.magi.ygodiscordbot.entity.banlist.Banlist;
import at.magi.ygodiscordbot.entity.banlist.EdisonBanlist;
import at.magi.ygodiscordbot.entity.banlist.GoatBanlist;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class StaticBanlistsTest {

    @DataProvider
    public static Object[][] banlists() {
        return new Object[][]{
                {"goat", StaticBanlists.goat(), "April 2005", 73, 17, 41, 15},
                {"edison", StaticBanlists.edison(), "March 2010", 132, 43, 70, 19}};
    }

    private static String nameOf(Banlist banlist) {
        return banlist instanceof GoatBanlist goat ? goat.name() : ((EdisonBanlist) banlist).name();
    }

    @Test(dataProvider = "banlists")
    public void loadsBundledBanlist(String format, Banlist banlist, String name, int entries, int forbidden,
                                    int limited, int semiLimited) {
        assertEquals(nameOf(banlist), name, format);
        assertEquals(banlist.entries().size(), entries, format);
        assertEquals(banlist.withStatus(BanStatus.FORBIDDEN).size(), forbidden, format);
        assertEquals(banlist.withStatus(BanStatus.LIMITED).size(), limited, format);
        assertEquals(banlist.withStatus(BanStatus.SEMI_LIMITED).size(), semiLimited, format);
    }
}
