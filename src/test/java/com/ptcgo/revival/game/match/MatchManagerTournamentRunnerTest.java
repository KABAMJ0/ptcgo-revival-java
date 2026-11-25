package com.ptcgo.revival.game.match;

import static org.junit.jupiter.api.Assertions.*;

import com.ptcgo.revival.game.GameFixtures;
import com.ptcgo.revival.game.RecordingPlayer;
import com.ptcgo.revival.game.TestCatalog;
import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.engine.GameSession;
import com.ptcgo.revival.tournament.TournamentMatchRunner;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class MatchManagerTournamentRunnerTest {

    @Test
    void finishedTournamentGameReportsOneWinnerAndTheTournamentOption() throws Exception {
        RecordingPlayer a = GameFixtures.player(GameFixtures.A, "botA", TestCatalog.lightningDeck());
        RecordingPlayer b = GameFixtures.player(GameFixtures.B, "botB", TestCatalog.lightningDeck());
        GameSession s = GameFixtures.aiVsAi(11, EffectRegistry.empty(), a, b);
        List<String[]> results = new CopyOnWriteArrayList<>();
        s.attachTournament("tourney-1", (gameId, winnerId, reason) -> results.add(new String[] {gameId, winnerId, reason}));

        GameFixtures.runToCompletion(s, 60_000);

        assertEquals(1, results.size(), "the bracket callback must fire exactly once");
        String[] r = results.get(0);
        assertEquals(GameFixtures.GAME, r[0]);
        assertEquals(s.winnerId(), r[1]);
        assertTrue(r[1].equals(GameFixtures.A) || r[1].equals(GameFixtures.B), "winner must be a seat: " + r[1]);
        assertEquals(s.resultReason(), r[2]);

        // the tournament id rides in gameOptions of MatchFound, as the Python engine sends it
        boolean seen = false;
        for (RecordingPlayer.Sent sent : a.sent) {
            if (sent.name().equals("MatchFound")) {
                assertEquals("tourney-1", sent.value().path("gameOptions").path("TournamentID").asText());
                seen = true;
            }
        }
        assertTrue(seen, "no MatchFound sent to the player");
    }

    @Test
    void reasonMapsToOutcome() {
        assertEquals(TournamentMatchRunner.Outcome.CONCEDED,
                MatchManagerTournamentRunner.outcomeOf("botA conceded"));
        assertEquals(TournamentMatchRunner.Outcome.TIMED_OUT,
                MatchManagerTournamentRunner.outcomeOf("botB ran out of time to make a selection"));
        assertEquals(TournamentMatchRunner.Outcome.COMPLETED,
                MatchManagerTournamentRunner.outcomeOf("botA has no Pokémon left"));
        assertEquals(TournamentMatchRunner.Outcome.COMPLETED, MatchManagerTournamentRunner.outcomeOf(null));
    }
}
