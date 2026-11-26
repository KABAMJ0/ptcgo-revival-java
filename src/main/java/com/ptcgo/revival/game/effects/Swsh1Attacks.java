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

        // ---- energy discard, mill and draw --------------------------------------------------------------------------
        // Cinderace_34 "Flare Striker" 190: self_energy_discard_attack(count=2)
        attack(r, "9a908f80-98fe-5529-aeb7-aa5aaf67fdbe", 1, AttackEffects.selfEnergyDiscard(2, false, null));
        // Cinderace_35 "Burning Kick" 160: self_energy_discard_attack(all_energy=True)
        attack(r, "76cf758f-cc7a-5d04-9516-6b243ced4faa", 1, AttackEffects.selfEnergyDiscard(0, true, null));
        // Salazzle_28 "Bright Flame" 120: self_energy_discard_attack(count=2)
        attack(r, "8147aaac-109a-544a-a64d-8a00320d3758", 1, AttackEffects.selfEnergyDiscard(2, false, null));
        // Scorbunny_30 "Ember" 30: self_energy_discard_attack(count=1)
        attack(r, "ceab92cb-833e-54da-8923-40c11485179a", 0, AttackEffects.selfEnergyDiscard(1, false, null));
        // Turtonator_29 "Fire Spin" 150: self_energy_discard_attack(count=2)
        attack(r, "70e56c17-ebf7-5636-a7c3-f95f38c29d9e", 1, AttackEffects.selfEnergyDiscard(2, false, null));
        // Mawile_129 "Crunch" 40: discard_opponent_energy_attack()
        attack(r, "4f0d77cc-19f5-52c9-85b9-f5c914f150f1", 1, AttackEffects.discardOpponentEnergy(1, false));
        // TorkoalV_24 / TorkoalV_188 "Steam Crush" 120: discard_opponent_energy_attack(count=2)
        attack(r, "7c706994-46ea-57df-8336-9c1dd1563e12", 1, AttackEffects.discardOpponentEnergy(2, false));
        attack(r, "10c5b818-6abf-50d7-a391-7ad6a0d31e46", 1, AttackEffects.discardOpponentEnergy(2, false));
        // ZamazentaV_139 / _196 / _212 "Assault Tackle" 130: discard_opponent_energy_attack(special_only=True)
        attack(r, "61323c30-4785-5617-9967-087dd01de004", 1, AttackEffects.discardOpponentEnergy(1, true));
        attack(r, "aaae6968-9dcc-5b93-970a-7af10408f106", 1, AttackEffects.discardOpponentEnergy(1, true));
        attack(r, "7256e884-9c4d-5004-aa26-cc71c361f26d", 1, AttackEffects.discardOpponentEnergy(1, true));
        // Drampa_149 "Dragon Pulse" 80: mill_attack(2, opponent=False)
        attack(r, "5e54416d-c387-5bf5-90d1-b0d8ab4ec408", 0, AttackEffects.mill(2, false));
        // Kingler_44 "Heavy Pincers" 40: mill_attack(1)
        attack(r, "da38bf32-8fcf-54c2-926d-99bb2cc8ee4b", 0, AttackEffects.mill(1, true));
        // Rhyhorn_97 "Stomp Off" 0: mill_attack(2)
        attack(r, "43af9110-c618-53a7-a962-c631c148afed", 0, AttackEffects.mill(2, true));
        // Goldeen_46 "Collect" 0: draw_attack(1)
        attack(r, "5a22d19c-1c99-5b1a-9a49-b1bd190d0f66", 0, AttackEffects.drawAttack(1));
        // Sudowoodo_100 "Double Draw" 0: draw_attack(2)
        attack(r, "f5463868-6cf5-58e5-9d26-ef04c5886e49", 0, AttackEffects.drawAttack(2));
        // TapuKokoV_72 "Spike Draw" 20: draw_attack(2)
        attack(r, "ecef3f1d-c12c-50f6-85ed-e869f10f9116", 0, AttackEffects.drawAttack(2));
        return r;
    }
}
