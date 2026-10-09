package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.Standings;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TournamentMessagesTest {

    @Test
    public void roundStartListsMatchIdsAndByes() {
        String post = String.join("\n", TournamentMessages.roundStart(12, 2,
                List.of(new ActiveMatch(48213, 12, 2, 1, 2)), List.of(3L)));
        assertTrue(post.contains("Tournament #12 · Round 2"), post);
        assertTrue(post.contains("`48213` · <@1> vs <@2>"), post);
        assertTrue(post.contains("<@3> gets a free win"), post);
        assertTrue(post.contains("/match finish"), post);
        assertTrue(post.contains("/match doubleloss"), post);
    }

    @Test
    public void equalRecordsShareARank() {
        Standings standings = Standings.of(List.of(1L, 2L, 3L, 4L), Set.of(4L), List.of(
                new MatchRecord(1, 1, 2L, 1L), new MatchRecord(1, 3, 4L, 3L)));
        assertEquals(TournamentMessages.standingsLines(standings), List.of(
                "1. <@1> · 1-0", "1. <@3> · 1-0", "3. <@2> · 0-1", "3. <@4> · 0-1 (dropped)"));
    }

    @Test
    public void resultsNameWinnersAndByes() {
        Standings standings = Standings.of(List.of(1L, 2L, 3L), Set.of(), List.of(
                new MatchRecord(1, 1, 2L, 2L), MatchRecord.of(1, Pairing.bye(3))));
        String post = String.join("\n", TournamentMessages.roundResults(5, 1,
                List.of(new MatchRecord(1, 1, 2L, 2L), MatchRecord.of(1, Pairing.bye(3))), standings));
        assertTrue(post.contains("<@2> beat <@1>"), post);
        assertTrue(post.contains("<@3> had a free win"), post);
    }

    @Test
    public void doubleLossesNameBothPlayers() {
        MatchRecord doubleLoss = new MatchRecord(1, 1, 2L, null, true);
        Standings standings = Standings.of(List.of(1L, 2L), Set.of(), List.of(doubleLoss));
        String post = String.join("\n", TournamentMessages.roundResults(5, 1, List.of(doubleLoss), standings));
        assertTrue(post.contains("<@1> and <@2> both lose (time limit)"), post);
        assertTrue(TournamentMessages.alreadyFinished(48213, doubleLoss).contains("double loss"));
    }

    @Test
    public void winnerPostWarnsAboutUnsavedPoints() {
        Standings standings = Standings.of(List.of(1L, 2L), Set.of(), List.of(new MatchRecord(1, 1, 2L, 1L)));
        String post = String.join("\n", TournamentMessages.winner(5, 1, 1, standings, Map.of(1L, 2L), List.of(1L)));
        assertTrue(post.contains("<@1> wins after 1 round!"), post);
        assertTrue(post.contains("<@1> +2"), post);
        assertTrue(post.contains("not saved"), post);
    }

    @Test
    public void nextPairingsTellHowToContinue() {
        String post = String.join("\n", TournamentMessages.nextPairings(5, 2,
                List.of(new MatchRecord(2, 1, 3L, null), MatchRecord.of(2, Pairing.bye(2)))));
        assertTrue(post.contains("<@1> vs <@3>"), post);
        assertTrue(post.contains("<@2> gets a free win"), post);
        assertTrue(post.contains("/tournament continue id:5"), post);
    }
}
