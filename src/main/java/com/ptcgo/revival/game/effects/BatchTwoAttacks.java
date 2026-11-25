package com.ptcgo.revival.game.effects;

/**
 * Second batch of ported card attacks (Standard SV sets). Keys are the abilityID the client sees in PIE_ABILITIES
 * (taken from the catalogue export's attribute 200740). Each entry names its Python card script in a comment so the
 * behaviour can be checked against it. Every attack here uses one of the helpers already in {@link AttackEffects}.
 */
public final class BatchTwoAttacks {
    private BatchTwoAttacks() {}

    /** Registers the 20 batch-two attacks on {@code registry}. */
    public static EffectRegistry register(EffectRegistry registry) {
        // SV5 Sizzlipede 36 "Heat Dive" 30: recoil_attack(10)
        registry.attack("11fe8b65-3f81-5465-8300-1b2e8ef2bf1e", EffectRegistry.AttackSpec.of(AttackEffects.recoil(10)));
        // SV6 Aron 120 "Double-Edge" 20: recoil_attack(10)
        registry.attack("d29de89d-3d43-5c0d-a7df-cf6009e91f7e", EffectRegistry.AttackSpec.of(AttackEffects.recoil(10)));
        // SV7 Klang 100 "Iron Tackle" 60: recoil_attack(20)
        registry.attack("0c3c7cce-51ab-5300-854c-6320e9232c79", EffectRegistry.AttackSpec.of(AttackEffects.recoil(20)));
        // SV6 Rillaboom 16 "Wood Hammer" 180: recoil_attack(50)
        registry.attack("79cf510d-aab3-5ae5-a5e6-bb27cf97678f", EffectRegistry.AttackSpec.of(AttackEffects.recoil(50)));
        // SV8 Flamigo ex 160 "Brave Bird" 200: recoil_attack(30)
        registry.attack("17cfcf34-e7a3-5a18-b4a3-678289075bc9", EffectRegistry.AttackSpec.of(AttackEffects.recoil(30)));
        // SV5 Nuzleaf 4 "Comet Slap" 30: flip_damage(coins=3, per_heads=30)
        registry.attack("895f5a3f-1ab5-583e-8d5d-787c2378faa0", EffectRegistry.AttackSpec.of(AttackEffects.flipPerHeads(3, 30)));
        // SV5 Duosion 71 "Double Trick" 30: flip_damage(coins=2, per_heads=30)
        registry.attack("326fbeca-1015-5241-aa4d-d21c211494dd", EffectRegistry.AttackSpec.of(AttackEffects.flipPerHeads(2, 30)));
        // SV6 Poliwag 41 "Tail Rap" 20: flip_damage(coins=2, per_heads=20)
        registry.attack("4258bdba-90d0-5df0-a57a-94bd1e21564a", EffectRegistry.AttackSpec.of(AttackEffects.flipPerHeads(2, 20)));
        // SV7 Melmetal ex 105 "Iron Swing" 100: flip_damage(coins=2, per_heads=100)
        registry.attack("ca052cd6-6eec-5758-8a16-3a6569cc0871", EffectRegistry.AttackSpec.of(AttackEffects.flipPerHeads(2, 100)));
        // SV8 Maushold 158 "Incessant Incisors" 30: flip_damage(coins=4, per_heads=30)
        registry.attack("b97a7dae-fa0c-5f3a-bd89-bd26ea946855", EffectRegistry.AttackSpec.of(AttackEffects.flipPerHeads(4, 30)));
        // SV5 Azumarill 65 "Play Rough" 30: flip_bonus(30)
        registry.attack("b9537570-1f56-5385-9a60-fd4e062a7644", EffectRegistry.AttackSpec.of(AttackEffects.flipBonus(30, 1)));
        // SV7 Greninja ex 41 "Shifting Shuriken" 100: flip_bonus(100)
        registry.attack("67235615-c2bc-5e0b-8091-3a28a8f1fe6e", EffectRegistry.AttackSpec.of(AttackEffects.flipBonus(100, 1)));
        // SV7 Rapidash 20 "Burning Run" 60: flip_bonus(60)
        registry.attack("d4c9d628-f5a8-51e8-9cf4-1ffe9fe24a35", EffectRegistry.AttackSpec.of(AttackEffects.flipBonus(60, 1)));
        // SV8 Crocalor 30 "Heat Breath" 30: flip_bonus(50)
        registry.attack("ed6e3d1b-0e3e-56cb-9b98-ca101097591e", EffectRegistry.AttackSpec.of(AttackEffects.flipBonus(50, 1)));
        // SV5 Delcatty 131 "Tail Trickery" 30: condition_attack(CONFUSED)
        registry.attack("0a110880-5713-5821-b8ec-f04b901479e1", EffectRegistry.AttackSpec.of(
                AttackEffects.condition(false, 0, 0, "Confused")));
        // SV5 Snom 168 "Powder Snow" 10: condition_attack(ASLEEP)
        registry.attack("2d22c471-d83b-5431-bce6-b2f8bcc95672", EffectRegistry.AttackSpec.of(
                AttackEffects.condition(false, 0, 0, "Asleep")));
        // SV6 Bellibolt 74 "Thunder Shock" 50: condition_attack(PARALYZED, flip=True)
        registry.attack("52ca1315-f673-5dd8-a100-13eeaace8065", EffectRegistry.AttackSpec.of(
                AttackEffects.condition(true, 1, 1, "Paralyzed")));
        // SV7 Venusaur ex 1 "Dangerous Toxwhip" 150: condition_attack(CONFUSED, POISONED)
        registry.attack("679b047d-bbf9-5f2e-92e6-dd99d7be33f5", EffectRegistry.AttackSpec.of(
                AttackEffects.condition(false, 0, 0, "Confused", "Poisoned")));
        // SV7 Pansear 21 "Searing Flame" 20: condition_attack(BURNED)
        registry.attack("f7d9c2b4-930e-52a7-a7a0-e4df3b5e8ccf", EffectRegistry.AttackSpec.of(
                AttackEffects.condition(false, 0, 0, "Burned")));
        // SV6PT5 Iron Moth 9 "Suction" 30: heal_dealt_attack()
        registry.attack("f8f3015c-ca38-561c-96c4-ecae84915603", EffectRegistry.AttackSpec.of(AttackEffects.healDealt()));
        return registry;
    }
}
