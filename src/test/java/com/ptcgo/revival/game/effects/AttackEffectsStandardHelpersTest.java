package com.ptcgo.revival.game.effects;

import static com.ptcgo.revival.game.effects.BatchTwoAttacksTest.attack;
import static com.ptcgo.revival.game.effects.BatchTwoAttacksTest.fixture;
import static com.ptcgo.revival.game.effects.BatchTwoAttacksTest.hp;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks the four helpers added for the standard batch two against the Python bodies they mirror in
 * {@code ptcgo/game/card_effects/} (the expected numbers follow from those bodies; see each test's comment). Uses the
 * BatchTwoAttacksTest fixtures: the same scripted coin flips and the same 300 HP Pokemon.
 */
class AttackEffectsStandardHelpersTest {
    private static final String GUID = "c0000000-0000-4000-8000-000000000001";
    private static final String ID = "c0000000-0000-4000-8000-0000000000aa";
    private static final String TITLE = "Test Attack";

    // ---- heal_attack(amount) ------------------------------------------------------------------------------------

    @Test
    void healSelfDealsPrintedDamageThenHealsTheAttacker() {
        // Python heal_attack(amount=30): printed damage to the Defending Pokemon, then heal 30 from this Pokemon.
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Healer", ID, TITLE, 20, 200);
        AttackEffects.healSelf(30).resolve(f.ctx());
        assertEquals(280, hp(f.defender()), "printed 20 to the defender");
        assertEquals(230, hp(f.attacker()), "healed 30 from 200");
    }

    @Test
    void healSelfNeverHealsPastMaxHp() {
        // Python heals min(max_hp, current + amount): a 290/300 attacker with heal 30 ends at 300.
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Healer", ID, TITLE, 20, 290);
        AttackEffects.healSelf(30).resolve(f.ctx());
        assertEquals(300, hp(f.attacker()));
    }

    // ---- flip_or_nothing(coins) ---------------------------------------------------------------------------------

    @Test
    void flipOrNothingDealsPrintedDamageOnlyOnAllHeads() {
        // Python flip_or_nothing(coins=2): any tails returns before the printed damage.
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Flipper", ID, TITLE, 60, 300, true, true);
        AttackEffects.flipOrNothing(2).resolve(f.ctx());
        assertEquals(240, hp(f.defender()), "all heads deals printed 60");
        f.ops().rng.assertAllUsed();
    }

    @Test
    void flipOrNothingDoesNothingWhenAnyCoinIsTails() {
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Flipper", ID, TITLE, 60, 300, true, false);
        AttackEffects.flipOrNothing(2).resolve(f.ctx());
        assertEquals(300, hp(f.defender()), "a tails means no damage");
        f.ops().rng.assertAllUsed();
    }

    @Test
    void flipOrNothingDoesNothingOnAllTails() {
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Flipper", ID, TITLE, 60, 300, false, false);
        AttackEffects.flipOrNothing(2).resolve(f.ctx());
        assertEquals(300, hp(f.defender()));
        f.ops().rng.assertAllUsed();
    }

    // ---- condition_bonus_attack(bonus, *conditions) -------------------------------------------------------------

    @Test
    void conditionBonusAddsBonusWhenDefenderAlreadyHasTheCondition() {
        // Python condition_bonus_attack(40, ASLEEP): printed 20 + 40 when the Defending Pokemon is already Asleep.
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Bonus", ID, TITLE, 20, 300);
        f.defender().setConditions(new ArrayList<>(List.of("Asleep")));
        AttackEffects.conditionBonus(40, "Asleep").resolve(f.ctx());
        assertEquals(240, hp(f.defender()));
        assertEquals(List.of("Asleep"), f.defender().conditions(), "the check applies no condition");
    }

    @Test
    void conditionBonusIsPrintedOnlyWithoutTheCondition() {
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Bonus", ID, TITLE, 20, 300);
        AttackEffects.conditionBonus(40, "Asleep").resolve(f.ctx());
        assertEquals(280, hp(f.defender()));
    }

    @Test
    void conditionBonusNeedsEveryNamedCondition() {
        // Python: met = all(n in current for n in names), so only Asleep present is not enough for Asleep+Poisoned.
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Bonus", ID, TITLE, 20, 300);
        f.defender().setConditions(new ArrayList<>(List.of("Asleep")));
        AttackEffects.conditionBonus(40, "Asleep", "Poisoned").resolve(f.ctx());
        assertEquals(280, hp(f.defender()));
    }

    // ---- flip_damage_condition(coins, per_heads=, min_heads=, condition=) ---------------------------------------

    @Test
    void flipDamageConditionDealsPerHeadsAndAppliesConditionAtMinHeads() {
        // Python: base 0 when per_heads is set; 1 heads x 20 = 20; heads >= min_heads(1) so the condition applies.
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Shock", ID, TITLE, 0, 300, true, false);
        AttackEffects.flipDamageCondition(2, 20, 1, "Paralyzed").resolve(f.ctx());
        assertEquals(280, hp(f.defender()));
        assertTrue(f.defender().conditions().contains("Paralyzed"));
        f.ops().rng.assertAllUsed();
    }

    @Test
    void flipDamageConditionSkipsConditionBelowMinHeads() {
        // One heads is below min_heads=2: the damage still happens, the condition does not.
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Shock", ID, TITLE, 0, 300, true, false);
        AttackEffects.flipDamageCondition(2, 20, 2, "Paralyzed").resolve(f.ctx());
        assertEquals(280, hp(f.defender()));
        assertFalse(f.defender().conditions().contains("Paralyzed"));
    }

    @Test
    void flipDamageConditionWithZeroPerHeadsKeepsPrintedDamageOnEveryOutcome() {
        // Python: with per_heads=0 the base is the printed damage (30) whatever the flips; the condition needs heads.
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Burner", ID, TITLE, 30, 300, false);
        AttackEffects.flipDamageCondition(1, 0, 1, "Burned").resolve(f.ctx());
        assertEquals(270, hp(f.defender()), "printed damage on tails");
        assertFalse(f.defender().conditions().contains("Burned"));
        f.ops().rng.assertAllUsed();
    }

    @Test
    void flipDamageConditionWithNullConditionOnlyChangesDamage() {
        BatchTwoAttacksTest.Fixture f = fixture(GUID, "Burner", ID, TITLE, 0, 300, true);
        AttackEffects.flipDamageCondition(1, 30, 1, null).resolve(f.ctx());
        assertEquals(270, hp(f.defender()));
        assertTrue(f.defender().conditions().isEmpty());
    }
}
