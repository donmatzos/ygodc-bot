package at.magi.ygodiscordbot.leaderboard;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class PointsTest {

    @Test
    public void addsAndRemoves() {
        assertEquals(Points.apply(10, 5), 15);
        assertEquals(Points.apply(10, -5), 5);
    }

    @Test
    public void removeStopsAtZero() {
        assertEquals(Points.apply(5, -99), 0);
        assertEquals(Points.apply(0, -1), 0);
    }

    @Test
    public void maximumIs999999() {
        assertEquals(Points.MAX, 999_999);
    }

    @Test
    public void addStopsAtMaximum() {
        assertEquals(Points.apply(999_950, 99), 999_999);
        assertEquals(Points.apply(999_999, 1), 999_999);
        assertEquals(Points.apply(999_900, 99), 999_999);
        assertEquals(Points.apply(999_899, 99), 999_998);
    }

    @Test
    public void appliedIsTheRealChange() {
        assertEquals(new PointChange(2, 0, false).applied(), -2);
        assertEquals(new PointChange(0, 3, true).applied(), 3);
    }
}
