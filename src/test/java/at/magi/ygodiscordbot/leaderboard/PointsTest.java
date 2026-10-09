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
    public void addStopsAtMaximum() {
        assertEquals(Points.MAX, Long.MAX_VALUE - 1);
        assertEquals(Points.apply(Points.MAX - 1, 99), Points.MAX);
        assertEquals(Points.apply(Points.MAX, 1), Points.MAX);
    }

    @Test
    public void noOverflowNearLongMax() {
        assertEquals(Points.apply(Points.MAX - 99, 99), Points.MAX);
        assertEquals(Points.apply(Points.MAX - 100, 99), Points.MAX - 1);
    }

    @Test
    public void valueAboveMaximumIsPulledDown() {
        // Only possible if someone edited the table by hand
        assertEquals(Points.apply(Long.MAX_VALUE, 5), Points.MAX);
        assertEquals(Points.apply(Long.MAX_VALUE, -5), Points.MAX - 5);
    }

    @Test
    public void appliedIsTheRealChange() {
        assertEquals(new PointChange(2, 0, false).applied(), -2);
        assertEquals(new PointChange(0, 3, true).applied(), 3);
    }
}
