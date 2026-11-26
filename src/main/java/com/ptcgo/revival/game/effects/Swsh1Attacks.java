package com.ptcgo.revival.game.effects;

import static com.ptcgo.revival.game.effects.AttackEffects.ConditionOptions;
import static com.ptcgo.revival.game.wire.Attr.ASLEEP;
import static com.ptcgo.revival.game.wire.Attr.BURNED;
import static com.ptcgo.revival.game.wire.Attr.PARALYZED;
import static com.ptcgo.revival.game.wire.Attr.POISONED;

import com.ptcgo.revival.progression.Uuid5;

import java.util.UUID;

/**
 * Sword &amp; Shield base set (SWSH1) attacks, ported from the Python card scripts in
 * {@code ptcgo/game/scripts/cards/SWSH1/}. Each entry names the card file, slot and the Python effect it mirrors.
 * Attack ids are derived exactly as Python's {@code ability_id_for(card_guid, slot)} does.
 */
public final class Swsh1Attacks {
    private Swsh1Attacks() {}

    /** Python {@code _ABILITY_ID_NAMESPACE} (ptcgo/game/content/definitions.py). */
    static final UUID ABILITY_ID_NAMESPACE = UUID.fromString("a3f2c6e8-9d41-4d7a-8b5f-2e7c90d13a64");

    /** Python {@code ability_id_for(card_guid, slot)}. */
    public static String abilityId(String cardGuid, int slot) {
        return Uuid5.of(ABILITY_ID_NAMESPACE, cardGuid + ":ability:" + slot).toString();
    }

    private static void attack(EffectRegistry r, String cardGuid, int slot, AttackEffect effect) {
        r.attack(abilityId(cardGuid, slot), EffectRegistry.AttackSpec.of(effect));
    }

    private static final int FIGHTING = 5, PSYCHIC = 10, WATER = 11;

    public static EffectRegistry register(EffectRegistry r) {
        // ---- helpers already in AttackEffects (these printings were not registered by the earlier batches) ----------
        // Centiskorch_39 "Searing Flame" 110: condition_attack(BURNED)
        attack(r, "5ab3accb-bf6e-526f-85c9-fd65413f0e64", 1, AttackEffects.condition(false, 0, 0, BURNED));
        // Clobbopus_111 "Bind" 20: condition_attack(PARALYZED, flip=True)
        attack(r, "98a1a6a4-4f0e-5dff-9536-252c5cf2b20c", 0, AttackEffects.condition(true, 1, 1, PARALYZED));
        // GalarianPonyta_81 "Psy Bolt" 10: condition_attack(PARALYZED, flip=True)
        attack(r, "9c37cbc6-f755-5bf8-b252-55099c40509a", 0, AttackEffects.condition(true, 1, 1, PARALYZED));
        // Dubwool_154 "Double-Edge" 120: recoil_attack(30)
        attack(r, "c3fa38ce-33c2-5033-9ffb-07c430cf3a49", 1, AttackEffects.recoil(30));
        // GalarianZigzagoon_117 "Surprise Attack" 30: flip_or_nothing()
        attack(r, "e3800492-f270-5d13-8cb3-7b695565caea", 1, AttackEffects.flipOrNothing(1));
        // Grookey_10 "Fury Swipes" 10x: flip_damage(coins=3, per_heads=10)
        attack(r, "b7fce58a-eed8-5d50-91ad-056c9af03b78", 0, AttackEffects.flipPerHeads(3, 10));
        // Minccino_145 "Tail Slap" 20x: flip_damage(coins=2, per_heads=20)
        attack(r, "8b5d3f00-2323-56c4-a204-eb60c05ac21e", 1, AttackEffects.flipPerHeads(2, 20));

        // ---- damage_per ---------------------------------------------------------------------------------------------
        // Bisharp_134 "Charge Order" 30x: damage_per(count_bench('mine', pred=_is_pawniard), 30, base=30)
        attack(r, "e39ed81f-f4ec-5489-b7bc-4890749fa45e", 0,
                AttackEffects.damagePer(Counters.countBench("mine", Counters.logicNameIs("Pawniard")), 30, 30));
        // CelebiV_1 "Line Force" 50+: damage_per(count_bench('mine'), 20, base=50)
        attack(r, "93c4af72-ab2c-566a-871f-9834d9db7e9c", 1, AttackEffects.damagePer(Counters.countBench("mine"), 20, 50));
        // GalarianRapidash_82 "Psychic" 30+: damage_per(count_energy('defender'), 30, base=30)
        attack(r, "ce15658e-4699-5663-978e-87cd51033118", 1, AttackEffects.damagePer(Counters.countEnergy("defender"), 30, 30));
        // IndeedeeV_91 / IndeedeeV_192 "Psychic" 10+: damage_per(count_energy('defender'), 60, base=10)
        attack(r, "d079a361-f311-580b-8efc-62acca8e08af", 1, AttackEffects.damagePer(Counters.countEnergy("defender"), 60, 10));
        attack(r, "e736338d-3411-5b14-961e-3d4bae698613", 1, AttackEffects.damagePer(Counters.countEnergy("defender"), 60, 10));
        // KeldeoV_53 "Secret Sword" 50+: damage_per(count_energy('self', energy_type=WATER), 30, base=50)
        attack(r, "b27685d3-902a-5039-8132-7ca8811267d0", 1,
                AttackEffects.damagePer(Counters.countEnergy("self", WATER, false), 30, 50));
        // LaprasVMAX_50 / LaprasVMAX_203 "G-Max Pump" 90+: damage_per(count_energy('self', energy_type=WATER), 30, base=90)
        attack(r, "e91cd9dc-d345-5326-868d-93ee8b466e4a", 0,
                AttackEffects.damagePer(Counters.countEnergy("self", WATER, false), 30, 90));
        attack(r, "534ba9ef-f878-546c-90c2-9fc6659ad1e2", 0,
                AttackEffects.damagePer(Counters.countEnergy("self", WATER, false), 30, 90));
        // Orbeetle_19 "Brainwave" 90+: damage_per(count_energy('self', energy_type=PSYCHIC), 30, base=90)
        attack(r, "a3dc1dd1-a9fa-57e2-94dd-9186bf97ac4b", 1,
                AttackEffects.damagePer(Counters.countEnergy("self", PSYCHIC, false), 30, 90));
        // RegirockV_104 "Raging Hammer" 30+: damage_per(damage_counters_on('self'), 10, base=30)
        attack(r, "9f385d35-3e03-5e6f-a1e2-eb3802ff0fb6", 0, AttackEffects.damagePer(Counters.damageCountersOn("self"), 10, 30));
        // SableyeV_120 / SableyeV_194 "Crazy Claws" 10+: damage_per(damage_counters_on('defender'), 60, base=10)
        attack(r, "59eb1d89-ad28-5923-8d84-e867c63358cf", 1, AttackEffects.damagePer(Counters.damageCountersOn("defender"), 60, 10));
        attack(r, "14baca74-f776-5380-a052-8b40e807da1a", 1, AttackEffects.damagePer(Counters.damageCountersOn("defender"), 60, 10));
        // SnorlaxVMAX_142 / SnorlaxVMAX_206 "G-Max Fall" 60+: damage_per(count_bench('mine'), 30, base=60)
        attack(r, "43b577aa-2ead-5c2a-bf8b-ada729bca129", 0, AttackEffects.damagePer(Counters.countBench("mine"), 30, 60));
        attack(r, "ab662183-4c5a-5ed3-b7c3-614ad592e4b4", 0, AttackEffects.damagePer(Counters.countBench("mine"), 30, 60));
        // Sudowoodo_100 "Flail" 10x: damage_per(damage_counters_on('self'), 10)
        attack(r, "f5463868-6cf5-58e5-9d26-ef04c5886e49", 1, AttackEffects.damagePer(Counters.damageCountersOn("self"), 10, 0));
        // Drednaw_61 "Vise Bite" 60+: damage_per(_defender_retreat_cost, 30, base=60)
        attack(r, "c5f3a167-6b62-513b-9fde-d8e4e304c7b2", 0, AttackEffects.damagePer(Counters.defenderPrintedRetreatCost(), 30, 60));
        // VictiniV_25 "Energy Burst" 30x: damage_per(_both_actives_energy, 30)
        attack(r, "3b65f1a3-5299-5b0e-8a5b-524bf177645b", 1, AttackEffects.damagePer(Counters.bothActivesEnergy(), 30, 0));

        // ---- bonus_if -----------------------------------------------------------------------------------------------
        // Boltund_75 "Fighting Fangs" 90+: bonus_if(lambda ctx: defender_is_v(ctx) or defender_is_gx(ctx), 90)
        attack(r, "de957e31-726c-5a09-9d74-19f7a364a5d9", 1, AttackEffects.bonusIf(
                ctx -> Counters.defenderIsV().test(ctx) || Counters.defenderIsGx().test(ctx), 90, null, false));
        // Copperajah_137 "Muscular Nose" 220: bonus_if(lambda ctx: damage_counters_on('self')(ctx) < 8, 0, else_nothing=True)
        attack(r, "88bf3243-6420-5365-9449-6de59cd6dbbf", 1, AttackEffects.bonusIf(
                ctx -> Counters.damageCountersOn("self").count(ctx) < 8, 0, null, true));
        // Hitmonchan_95 "Coordinated Beatdown" 20+: bonus_if(named_in_play('Hitmonlee'), 20, base=20)
        attack(r, "18ac4b65-9532-5b79-abfb-4122e9a3a7ae", 0,
                AttackEffects.bonusIf(Counters.namedInPlay("mine", "Hitmonlee"), 20, 20, false));
        // Hitmonlee_94 "Coordinated Strike" 80+: bonus_if(named_in_play('Hitmonchan'), 80, base=80)
        attack(r, "5e263812-b1aa-508b-b004-3ea7bd37efdd", 1,
                AttackEffects.bonusIf(Counters.namedInPlay("mine", "Hitmonchan"), 80, 80, false));
        // Kingler_44 "Claw Rend" 90+: bonus_if(has_damage(), 60)   (has_damage defaults to the Defending Pokemon)
        attack(r, "da38bf32-8fcf-54c2-926d-99bb2cc8ee4b", 1, AttackEffects.bonusIf(Counters.hasDamage("defender"), 60, null, false));
        // Morpeko_78 "Attack the Wound" 10+: bonus_if(has_damage('defender'), 50)
        attack(r, "5858b043-bc4a-5864-8bf8-f638f5004313", 0, AttackEffects.bonusIf(Counters.hasDamage("defender"), 50, null, false));
        // Musharna_88 "Super Hypnoblast" 10+: bonus_if(defender_has_condition(ASLEEP), 120)
        attack(r, "bb1f1731-14bd-59fd-8657-a7fb347b2b2b", 1,
                AttackEffects.bonusIf(Counters.defenderHasCondition(ASLEEP), 120, null, false));
        // Sandaconda_110 "Power Press" 60+: bonus_if(_extra_fighting_energy, 70)  (count_energy('self', FIGHTING) >= 3)
        attack(r, "e820b680-0fd1-5cbc-bcf2-f509b9950590", 1, AttackEffects.bonusIf(
                ctx -> Counters.countEnergy("self", FIGHTING, false).count(ctx) >= 3, 70, null, false));

        // ---- coin-flip variants -------------------------------------------------------------------------------------
        // GalarianStunfisk_132 "Damage Rush" 30+: flip_damage(until_tails=True, base=30, per_heads=30)
        attack(r, "59f91582-85c7-57c7-9fa5-7bc37ce7e13b", 1, AttackEffects.flipUntilTails(30, 30));
        // Sizzlipede_38 "Relentless Flames" 40x: flip_damage(until_tails=True, per_heads=40)
        attack(r, "5a8b1d66-0a2c-5f15-ac88-f3285e2b93a9", 0, AttackEffects.flipUntilTails(0, 40));
        // Maractus_7 "Powerful Needles" 60x: flip_damage(coins_from=count_energy('self'), per_heads=60)
        attack(r, "91548e48-5444-54ea-b2d2-d7a6eb2229a3", 1, AttackEffects.flipCoinsFrom(Counters.countEnergy("self"), 60));

        // ---- condition_attack with options --------------------------------------------------------------------------
        // Boltund_75 "Big Bite" 50: condition_attack(no_retreat=True)
        attack(r, "de957e31-726c-5a09-9d74-19f7a364a5d9", 0, AttackEffects.conditionAttack(ConditionOptions.of().withNoRetreat()));
        // Drednaw_61 "Jaw Lock" 130: condition_attack(no_retreat=True)
        attack(r, "c5f3a167-6b62-513b-9fde-d8e4e304c7b2", 1, AttackEffects.conditionAttack(ConditionOptions.of().withNoRetreat()));
        // Musharna_88 "Sleepy Pulse" 0: condition_attack(ASLEEP, checkup_coins=2)
        attack(r, "bb1f1731-14bd-59fd-8657-a7fb347b2b2b", 0,
                AttackEffects.conditionAttack(ConditionOptions.of(ASLEEP).withCheckupCoins(2)));
        // Roserade_4 "Paralyze Poison" 30: condition_attack(PARALYZED, flip=True, always_conditions=(POISONED,))
        attack(r, "683015b9-fae8-5341-aa5d-3c74a1e92f52", 0,
                AttackEffects.conditionAttack(ConditionOptions.of(PARALYZED).withFlip(1, 1).withAlways(POISONED)));
        // SnorlaxV_141 / SnorlaxV_197 "Falling Down" 170: condition_attack(self_conditions=(ASLEEP,))
        attack(r, "0782209a-a452-56a2-a139-627e4076a465", 1, AttackEffects.conditionAttack(ConditionOptions.of().withSelf(ASLEEP)));
        attack(r, "df8394b8-de15-51b8-92e0-7f3740ea024d", 1, AttackEffects.conditionAttack(ConditionOptions.of().withSelf(ASLEEP)));
        return r;
    }
}
