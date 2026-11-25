# Standard batch: verification against the Python source

Generated class: `src/main/java/com/ptcgo/revival/game/effects/GeneratedStandardBatch.java`
(IR: `tools/translator/ir/standard_batch.json`, manifest: `tools/translator/manifests/standard_batch.json`).

There is no hand port for these attacks, so the comparison below is a reading of each Python call against the factory
the emitter chose, and against the Python helper body it names. Nothing here was run against Python (no golden run).

## Selection

- Scanned 1309 Pokemon card scripts in SV5 and later (`SV5`, `SV6`, `SV6PT5`, `SV7`, `SV8`, `SV8PT5`, `SV9`, `SV10`).
- 109 cards qualify: every `Attack` on the card uses only `recoil_attack`, `flip_damage`, `flip_bonus`,
  `condition_attack` or `heal_dealt_attack`. Cards with any plain or other-helper attack are excluded.
- Ranking: attacks grouped by Pokemon name, attack title and effect call (one printing kept, the latest set). Sorted by
  how many printings share the same attack, then by set recency. The top 40 were taken.
- "Most common" is measured by printing count in the SV5+ scripts, not by play data.
- 5 of the top 40 use keyword forms the emitter does not model, so they are skipped (below). 35 attacks are generated
  from 34 cards.

## Verify table (generated attacks)

Match means the Java factory has the same shape and the same branch order as the Python helper body it names:
`flip_damage(per_heads)` = heads * per_heads with no printed damage; `flip_bonus` = printed plus bonus only on all heads
(printed always applies); `condition_attack` = printed, then conditions, with a flip gating conditions and flipping
before the damage; `recoil_attack` = printed, then self damage; `heal_dealt_attack` = printed, then heal the dealt amount.
Counts: 35 yes, 0 no. By factory: `condition` 19, `recoil` 10, `flipPerHeads`/`flipBonus` 6, `healDealt` 0.

| card | python effect call | java factory call | match |
|---|---|---|---|
| SV8PT5 Munkidori 44 "Mind Bend" | `condition_attack(SpecialConditions.CONFUSED)` | `AttackEffects.condition(false, 0, 0, "Confused")` | yes |
| SV8 Milotic ex 42 "Hypno Splash" | `condition_attack(SpecialConditions.ASLEEP)` | `AttackEffects.condition(false, 0, 0, "Asleep")` | yes |
| SV7 Dachsbun ex 67 "Wonder Shine" | `condition_attack(SpecialConditions.CONFUSED)` | `AttackEffects.condition(false, 0, 0, "Confused")` | yes |
| SV8PT5 Cottonee 7 "Triple Spin" | `flip_damage(coins=3, per_heads=10)` | `AttackEffects.flipPerHeads(3, 10)` | yes |
| SV8PT5 Eevee 74 "Reckless Charge" | `recoil_attack(10)` | `AttackEffects.recoil(10)` | yes |
| SV8 Clobbopus 207 "Slight Intrusion" | `recoil_attack(10)` | `AttackEffects.recoil(10)` | yes |
| SV8 Spheal 43 "Powder Snow" | `condition_attack(SpecialConditions.ASLEEP)` | `AttackEffects.condition(false, 0, 0, "Asleep")` | yes |
| SV7 Turtonator 25 "Inferno Onrush" | `recoil_attack(60)` | `AttackEffects.recoil(60)` | yes |
| SV6PT5 Tapu Bulu 65 "Wood Hammer" | `recoil_attack(30)` | `AttackEffects.recoil(30)` | yes |
| SV6 Heliolisk 71 "Wild Charge" | `recoil_attack(20)` | `AttackEffects.recoil(20)` | yes |
| SV6 Wattrel 75 "Thunder Shock" | `condition_attack(SpecialConditions.PARALYZED, flip=True)` | `AttackEffects.condition(true, 1, 1, "Paralyzed")` | yes |
| SV5 Incineroar ex 34 "Blaze Blast" | `condition_attack(SpecialConditions.BURNED)` | `AttackEffects.condition(false, 0, 0, "Burned")` | yes |
| SV5 Litten 32 "Fake Out" | `condition_attack(SpecialConditions.PARALYZED, flip=True)` | `AttackEffects.condition(true, 1, 1, "Paralyzed")` | yes |
| SV5 Snom 45 "Powder Snow" | `condition_attack(SpecialConditions.ASLEEP)` | `AttackEffects.condition(false, 0, 0, "Asleep")` | yes |
| SV10 Arrokuda 62 "Reckless Charge" | `recoil_attack(10)` | `AttackEffects.recoil(10)` | yes |
| SV10 Arven's Toedscool 109 "Slight Intrusion" | `recoil_attack(10)` | `AttackEffects.recoil(10)` | yes |
| SV10 Meditite 99 "Steady Punch" | `flip_bonus(20)` | `AttackEffects.flipBonus(20, 1)` | yes |
| SV10 Misty's Staryu 46 "Bubble Beam" | `condition_attack(SpecialConditions.PARALYZED, flip=True)` | `AttackEffects.condition(true, 1, 1, "Paralyzed")` | yes |
| SV10 Ponyta 29 "Double Headbutt" | `flip_damage(coins=2, per_heads=10)` | `AttackEffects.flipPerHeads(2, 10)` | yes |
| SV10 Team Rocket's Drowzee 79 "Hypnotic Ray" | `condition_attack(SpecialConditions.ASLEEP)` | `AttackEffects.condition(false, 0, 0, "Asleep")` | yes |
| SV10 Team Rocket's Flaaffy 73 "Thunder Shock" | `condition_attack(SpecialConditions.PARALYZED, flip=True)` | `AttackEffects.condition(true, 1, 1, "Paralyzed")` | yes |
| SV10 Team Rocket's Golbat 121 "Confuse Ray" | `condition_attack(SpecialConditions.CONFUSED)` | `AttackEffects.condition(false, 0, 0, "Confused")` | yes |
| SV10 Team Rocket's Rattata 147 "Dangerous Incisors" | `condition_attack(SpecialConditions.POISONED)` | `AttackEffects.condition(false, 0, 0, "Poisoned")` | yes |
| SV10 Team Rocket's Tarountula 19 "Take Down" | `recoil_attack(10)` | `AttackEffects.recoil(10)` | yes |
| SV10 Team Rocket's Zubat 120 "Poison Spray" | `condition_attack(SpecialConditions.POISONED)` | `AttackEffects.condition(false, 0, 0, "Poisoned")` | yes |
| SV9 Alolan Golem 46 "Megaton Fall" | `recoil_attack(40)` | `AttackEffects.recoil(40)` | yes |
| SV9 Amoonguss ex 11 "Spore Ball" | `condition_attack(SpecialConditions.ASLEEP)` | `AttackEffects.condition(false, 0, 0, "Asleep")` | yes |
| SV9 Amoonguss ex 11 "Champignon's Swing" | `flip_bonus(80)` | `AttackEffects.flipBonus(80, 1)` | yes |
| SV9 Foongus 10 "Spore Ball" | `condition_attack(SpecialConditions.ASLEEP)` | `AttackEffects.condition(false, 0, 0, "Asleep")` | yes |
| SV9 Hop's Snorlax 117 "Dynamic Press" | `recoil_attack(80)` | `AttackEffects.recoil(80)` | yes |
| SV9 Iono's Wattrel 54 "Quick Attack" | `flip_bonus(20)` | `AttackEffects.flipBonus(20, 1)` | yes |
| SV9 Magmar 20 "Searing Flame" | `condition_attack(SpecialConditions.BURNED, flip=True)` | `AttackEffects.condition(true, 1, 1, "Burned")` | yes |
| SV9 Magmortar 21 "Searing Flame" | `condition_attack(SpecialConditions.BURNED, flip=True)` | `AttackEffects.condition(true, 1, 1, "Burned")` | yes |
| SV9 N's Klink 103 "Double Spin" | `flip_damage(coins=2, per_heads=10)` | `AttackEffects.flipPerHeads(2, 10)` | yes |
| SV9 Noivern 128 "Frightening Howl" | `condition_attack(SpecialConditions.CONFUSED)` | `AttackEffects.condition(false, 0, 0, "Confused")` | yes |

`damage_operator` (`x`, `+`) and `game_text` are read by `dump_ast.py` and not emitted, as in batch one. Printed damage is
read from the card's ability entry at runtime, so the operator does not change the registration.

## Skipped (not generated)

Skip reason counts: 5 skipped. `until_tails`: 4 attacks (Spiral Rush also has `bonus_per_heads`). `no_retreat`: 1.
All are unmodelled keyword arguments of helpers that are in the modelled set, so no helper was invented.

| card | python effect call | reason |
|---|---|---|
| SV8 Shiinotic 9 "Spiral Rush" | `flip_damage(until_tails=True, bonus_per_heads=30)` | `until_tails`, `bonus_per_heads` not modelled |
| SV7 Turtonator 25 "Ring of Fire" | `condition_attack(SpecialConditions.BURNED, no_retreat=True)` | `no_retreat` not modelled |
| SV10 Mudbray 107 "Running Charge" | `flip_damage(per_heads=40, until_tails=True)` | `until_tails` not modelled |
| SV9 Alolan Golem 46 "Electromagnetic Catapult" | `flip_damage(per_heads=70, until_tails=True)` | `until_tails` not modelled |
| SV9 Cramorant 137 "Ceaseless Spitting" | `flip_damage(per_heads=50, until_tails=True)` | `until_tails` not modelled |

Each skip was confirmed by running dump_ast and emit_java on that attack alone; the emitter named the keyword each time.
The same entries are in the manifest `skipped` list.

## Not verified

- No Python golden run: behaviour is not executed against `ptcgo` or compared with live match results.
- Recoil self damage: Python passes `apply_modifiers=False`; the Java `recoil` factory calls
  `dealDamage(selfDamage, source, false)`, where the third argument is `asCounters`. Whether damage modifiers apply to
  that self damage is the existing batch-one semantics and is not re-checked here (10 of the 35 attacks use recoil).
- The Java flip factories compute the amount with `dealDamage(amount, null, false)`, matching Python's default
  `deal_damage`; this is read from the helper bodies, not executed.
- Catalog presence of each abilityID is checked by `GeneratedStandardBatchTest` (skipped if the export is absent).
