package at.magi.ygodiscordbot.entity.banlist;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class BanStatusTest {

    @DataProvider
    public static Object[][] labels() {
        return new Object[][]{
                {"Forbidden", BanStatus.FORBIDDEN}, {"Banned", BanStatus.FORBIDDEN},
                {" limited ", BanStatus.LIMITED}, {"Semi-Limited", BanStatus.SEMI_LIMITED},
                {"semi limited", BanStatus.SEMI_LIMITED}};
    }

    @Test(dataProvider = "labels")
    public void parsesSourceLabels(String label, BanStatus expected) {
        assertEquals(BanStatus.fromLabel(label), expected, "label '" + label + "'");
    }

    @Test
    public void rejectsUnknownLabel() {
        assertThrows(IllegalArgumentException.class, () -> BanStatus.fromLabel("Unlimited"));
    }
}
