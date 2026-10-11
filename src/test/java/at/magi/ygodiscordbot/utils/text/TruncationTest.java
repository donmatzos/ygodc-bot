package at.magi.ygodiscordbot.utils.text;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class TruncationTest {

    @DataProvider
    public Object[][] cases() {
        return new Object[][]{
                {"short", 10, "short"},
                {"exactly10!", 10, "exactly10!"},
                {"0123456789A", 10, "012345678…"},
                {"01234567\uD83D\uDE00AB", 10, "01234567…"}, // would otherwise cut the emoji in half
                {"abc", 1, "a"},
        };
    }

    @Test(dataProvider = "cases")
    public void truncatesToMax(String text, int max, String expected) {
        assertEquals(Truncation.truncate(text, max), expected);
    }
}
