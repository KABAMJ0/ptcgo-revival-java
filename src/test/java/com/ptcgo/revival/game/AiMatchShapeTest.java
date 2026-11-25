package com.ptcgo.revival.game;

import static org.junit.jupiter.api.Assertions.*;

import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.engine.GameSession;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A full headless AI-vs-AI match: it must finish, and every message must match what the Python server emitted. */
class AiMatchShapeTest {
    @Test
    void fullMatchConformsToPythonWireShapes() throws Exception {
        RecordingPlayer a = GameFixtures.player(GameFixtures.A, "botA", TestCatalog.lightningDeck());
        RecordingPlayer b = GameFixtures.player(GameFixtures.B, "botB", TestCatalog.lightningDeck());
        GameSession s = GameFixtures.aiVsAi(7, EffectRegistry.empty(), a, b);
        GameFixtures.runToCompletion(s, 60_000);
        assertNotNull(s.winnerId());

        PythonShapeOracle oracle = new PythonShapeOracle();
        Set<String> brackets = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (RecordingPlayer p : List.of(a, b)) {
            for (RecordingPlayer.Sent sent : p.sent) {
                if (!sent.name().equals("SequenceMessage")) {
                    assertTrue(Set.of("MatchFound", "SetIdleTimer", "ForceSelectionFinished", "CurrentWallet", "AccountUpdated")
                            .contains(sent.name()), "unexpected top-level message " + sent.name());
                    continue;
                }
                problems.addAll(oracle.checkSequenceMessage(sent.value(), brackets));
            }
        }
        assertTrue(problems.stream().distinct().limit(10).toList().isEmpty(), "shape problems: " + problems.stream().distinct().limit(10).toList());
        // every bracket name the Java engine opened is one the Python engine opens too
        Set<String> allowed = oracle.sequenceNames();
        Set<String> extra = new HashSet<>(brackets);
        extra.removeAll(allowed);
        assertTrue(extra.isEmpty(), "brackets unknown to Python: " + extra);
        for (String must : List.of("InitialCoinFlip", "DealInitialHands", "DealInitialPrizeCards", "InitialPick", "Draw",
                "PlayCard", "Attack", "Knockout", "WithOpenPrizeCards", "ActivePlayerSet", "TrainerCard", "Evolve")) {
            assertTrue(brackets.contains(must), "bracket never opened: " + must + " (opened " + brackets + ")");
        }
    }
}
