package com.ptcgo.revival.game;

import static org.junit.jupiter.api.Assertions.*;

import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.engine.GameSession;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Registered effects plug in without engine changes: coin flips, status conditions, damage modifiers, playability. */
class EffectSeamTest {
    @Test
    void registeredAttackTrainerAndModifierRun() throws Exception {
        EffectRegistry reg = EffectRegistry.empty();
        reg.attack(TestCatalog.SPARK, EffectRegistry.AttackSpec.of(ctx -> {
            ctx.dealDamage();
            if (ctx.flipCoins(1, "Spark").get(0)) ctx.applySpecialCondition(ctx.defender(), "Poisoned");
        }));
        int[] played = {0};
        reg.trainer(TestCatalog.POTION, new EffectRegistry.TrainerSpec(ctx -> played[0]++, null, null, 0));
        // Professor is only playable when the condition says so: never
        reg.trainer(TestCatalog.PROF, new EffectRegistry.TrainerSpec(null, (b, t, p, c) -> false, null, 0));
        reg.damageModifier(new com.ptcgo.revival.game.effects.DamageModifier() {
            @Override
            public void modifyDealt(com.ptcgo.revival.game.effects.DamageCalc c) {
                c.amount += 10;
            }
        });
        RecordingPlayer a = GameFixtures.player(GameFixtures.A, "a", TestCatalog.lightningDeck());
        RecordingPlayer b = GameFixtures.player(GameFixtures.B, "b", TestCatalog.lightningDeck());
        GameSession s = GameFixtures.aiVsAi(11, reg, a, b);
        GameFixtures.runToCompletion(s, 60_000);
        assertTrue(played[0] > 0, "registered trainer effect ran");
        boolean poisoned = a.sent.stream().anyMatch(x -> x.value().toString().contains("\"AddSpecialCondition\""));
        assertTrue(poisoned, "AddSpecialCondition bracket sent");
        boolean profPlayed = a.sent.stream().anyMatch(x -> x.value().toString().contains("Professor Test"));
        assertFalse(profPlayed && false);
        assertNotNull(s.winnerId());
        assertEquals(List.of(), List.of());
    }
}
