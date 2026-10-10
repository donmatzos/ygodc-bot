package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;
import org.testng.annotations.BeforeMethod;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

abstract class TournamentServiceTestBase {

    static final long GUILD = 1;
    static final long CHANNEL = 2;
    static final long ADMIN = 3;
    static final List<Long> FOUR = List.of(101L, 102L, 103L, 104L);

    FakeTournamentStore store;
    RecordingAnnouncer announcer;
    MutableClock clock;
    Map<Long, Long> awarded;
    TournamentService service;

    @BeforeMethod
    public void setUp() {
        store = new FakeTournamentStore();
        announcer = new RecordingAnnouncer();
        clock = new MutableClock(Instant.parse("2026-10-10T12:00:00Z"));
        awarded = new LinkedHashMap<>();
        service = newService(42);
    }

    /** A fresh service over the same store, like after a bot restart. */
    TournamentService newService(long seed) {
        return new TournamentService(store, (player, points) -> awarded.merge(player, points, Long::sum),
                announcer, clock, new Random(seed));
    }

    long start(List<Long> players) throws SQLException {
        service.start(GUILD, CHANNEL, ADMIN, players);
        return store.lastId();
    }

    /** Finishes every open match of the current round: player1 wins and reports it. Returns the last reply. */
    String playRound(long id) throws SQLException {
        String last = null;
        for (ActiveMatch match : service.openMatches(id)) {
            last = service.finishMatch(match.id(), GUILD, match.player1(), match.player1(), false);
        }
        return last;
    }

    /** A post or reply as the recording announcer shows it: names are "P" + user ID. */
    String rendered(NamedText text) {
        return String.join("\n", text.render().apply(RecordingAnnouncer.names(text.users())));
    }

    String code(long id) {
        return stored(id).code();
    }

    TournamentRecord stored(long id) {
        return store.load(id).orElseThrow();
    }
}
