package at.magi.ygodiscordbot.entity;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class BanStatusTest {

    @Test
    public void parsesSourceLabels() {
        assertEquals(BanStatus.fromLabel("Forbidden"), BanStatus.FORBIDDEN);
        assertEquals(BanStatus.fromLabel("Banned"), BanStatus.FORBIDDEN);
        assertEquals(BanStatus.fromLabel(" limited "), BanStatus.LIMITED);
        assertEquals(BanStatus.fromLabel("Semi-Limited"), BanStatus.SEMI_LIMITED);
    }

    @Test
    public void rejectsUnknownLabel() {
        assertThrows(IllegalArgumentException.class, () -> BanStatus.fromLabel("Unlimited"));
    }
}
