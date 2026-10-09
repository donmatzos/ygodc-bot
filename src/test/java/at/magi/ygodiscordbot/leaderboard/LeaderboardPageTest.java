package at.magi.ygodiscordbot.leaderboard;

import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class LeaderboardPageTest {

    @Test
    public void pageCountRoundsUp() {
        assertEquals(LeaderboardPage.pageCount(0), 0);
        assertEquals(LeaderboardPage.pageCount(1), 1);
        assertEquals(LeaderboardPage.pageCount(20), 1);
        assertEquals(LeaderboardPage.pageCount(21), 2);
        assertEquals(LeaderboardPage.pageCount(40), 2);
    }

    @Test
    public void offsetIsZeroBasedAndDoesNotOverflow() {
        assertEquals(LeaderboardPage.offset(1), 0L);
        assertEquals(LeaderboardPage.offset(2), 20L);
        assertEquals(LeaderboardPage.offset(Integer.MAX_VALUE), (Integer.MAX_VALUE - 1L) * 20);
    }

    @Test
    public void existsOnlyWithinRange() {
        assertTrue(new LeaderboardPage(1, 1, 5, List.of(new RankedPlayer(1, 42, 10))).exists());
        assertFalse(new LeaderboardPage(2, 1, 5, List.of()).exists());
        assertFalse(new LeaderboardPage(1, 0, 0, List.of()).exists());
    }
}
