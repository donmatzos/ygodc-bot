package at.magi.ygodiscordbot.impl.tournament;

import java.sql.SQLException;

/** A write that needs a RUNNING tournament hit one that is already finished or abandoned in the store. */
public class TournamentNotRunningException extends SQLException {

    public TournamentNotRunningException(long tournamentId) {
        super("Tournament " + tournamentId + " is not running");
    }
}
