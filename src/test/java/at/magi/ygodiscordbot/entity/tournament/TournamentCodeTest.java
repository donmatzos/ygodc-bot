package at.magi.ygodiscordbot.entity.tournament;

import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Random;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TournamentCodeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    @Test
    public void generatedCodeHasKeyAndDay() {
        String code = TournamentCode.generate(new Random(1), DAY);
        assertEquals(code.length(), TournamentCode.LENGTH);
        assertTrue(code.matches("[abcdefghjkmnpqrstuvwxyz23456789]{9}-26-10-10"), code);
    }

    @Test
    public void generatedCodesDiffer() {
        Random random = new Random(1);
        assertTrue(!TournamentCode.generate(random, DAY).equals(TournamentCode.generate(random, DAY)));
    }

    @Test
    public void parseTrimsAndLowercases() {
        assertEquals(TournamentCode.parse("  K7M2X9QP4-26-10-10 "), Optional.of("k7m2x9qp4-26-10-10"));
    }

    @Test
    public void parseRefusesOtherInput() {
        assertEquals(TournamentCode.parse("#12"), Optional.empty());
        assertEquals(TournamentCode.parse("k7m2x9qp-26-10-10"), Optional.empty());   // 8-char key
        assertEquals(TournamentCode.parse("k7m2x9qp0-26-10-10"), Optional.empty());  // 0 not in the alphabet
        assertEquals(TournamentCode.parse("k7m2x9qp4-26-02-30"), Optional.empty());  // no such day
        assertEquals(TournamentCode.parse(null), Optional.empty());
    }

    @Test
    public void dayRoundTrips() {
        assertEquals(TournamentCode.parseDay("26-10-10"), Optional.of(DAY));
        assertEquals(TournamentCode.parseDay(" 26-10-10 "), Optional.of(DAY));
        assertEquals(TournamentCode.day(DAY), "26-10-10");
        assertEquals(TournamentCode.parseDay("10-10-2026"), Optional.empty());
        assertEquals(TournamentCode.parseDay("26-13-01"), Optional.empty());
    }
}
