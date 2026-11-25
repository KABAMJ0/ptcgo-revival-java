package com.ptcgo.revival.game.effects;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Differential check: every attack registered by the generated {@link GeneratedBatchOne} must behave exactly like the
 * hand-ported {@link BatchOneAttacks} entry with the same abilityID. Both sides run through the same fixture and the
 * same scripted coin flips, and the observable state (HP, Special Conditions, poison counters, coin results, flips
 * consumed) must match. If they differ, fix the emitter, not this test.
 */
class GeneratedBatchOneTest {
    private static final int MAX_HP = 300;

    /** One batch-one attack and the coin-flip scripts to run it under (true = heads). */
    record Case(String abilityId, String cardGuid, String name, String title, int printed, int attackerHp,
                List<boolean[]> scripts) {}

    private static final boolean[] NO_FLIPS = {};

    private static List<boolean[]> none() {
        return List.of(NO_FLIPS);
    }

    private static List<boolean[]> scripts(boolean[]... s) {
        return List.of(s);
    }

    private static final List<Case> CASES = List.of(
            new Case("5464af08-f5c9-5d65-9831-0224ac44ae8f", "519fd25a-8b28-5552-846b-23f916fbef42", "Beldum",
                    "Iron Tackle", 50, MAX_HP, none()),
            new Case("725dfa86-be3e-59d7-a7ff-a5848828aa34", "be79dce9-59d1-5f83-9c2a-04ec67e21cab", "Excadrill",
                    "Wild Tackle", 180, MAX_HP, none()),
            new Case("156e287f-7d89-5c40-8fc5-f3be6251af7d", "eef1253e-f81e-573c-a96c-ba1ebb95fd4f", "Rapidash",
                    "Inferno Onrush", 120, MAX_HP, none()),
            new Case("68eab2f3-9627-5123-8f9a-2b2c013c4d12", "f70536a2-a562-5a49-bb55-e2bc21cf4abf", "Cubone",
                    "Reckless Charge", 30, MAX_HP, none()),
            new Case("387cdf26-00b5-59b0-a589-ba33ee7c5a2a", "3079e20d-cc24-50cf-99f9-43c85db38275", "Volcarona",
                    "Turbulent Wings", 150, MAX_HP, none()),
            new Case("efe1bd3a-9187-5bd4-a50b-ba7302f60350", "b1757be8-97f5-5326-8a72-dad91d0544ef", "Forretress",
                    "Spike Cannon", 30, MAX_HP,
                    scripts(new boolean[] {true, true, true}, new boolean[] {true, false, false},
                            new boolean[] {false, false, false}, new boolean[] {true, false, true})),
            new Case("0a04ce8a-e3eb-5c76-9f28-c59da0654271", "bde4995f-40fb-549b-8ba5-867874badde8", "Palafin",
                    "Double Hit", 90, MAX_HP,
                    scripts(new boolean[] {false, false}, new boolean[] {true, false},
                            new boolean[] {true, true})),
            new Case("3ba2bf0b-97e1-54ff-8b45-a8d1801941dd", "cc211d81-798e-5687-9f55-f4bf7023e41e", "Lickilicky",
                    "Tonguenado", 70, MAX_HP,
                    scripts(new boolean[] {false, false, false, false}, new boolean[] {true, false, true, false},
                            new boolean[] {true, true, true, true})),
            new Case("efd41a84-d71d-5233-97f1-0de344278de2", "fdaca5d0-bc3c-5312-bac2-ddafa86a0f00", "Carkol",
                    "Knock Away", 20, MAX_HP, scripts(new boolean[] {true}, new boolean[] {false})),
            new Case("87e1ad08-ffba-5e19-a7fe-b3714a9ac564", "82032ee6-c872-5cee-96d6-e4bea56ae320", "Eevee",
                    "Quick Attack", 20, MAX_HP, scripts(new boolean[] {true}, new boolean[] {false})),
            new Case("6b19ed4c-8699-5429-955d-750e30bc5506", "cc02c67b-528b-5f9d-ab0e-ff4e4e70bd5d", "Arbok",
                    "Panic Poison", 0, MAX_HP, none()),
            new Case("12c0dc7a-50ee-503d-b085-8f5aa3daa91f", "81e3fe26-5ed8-5733-bb0e-d63bc5c1f52b", "Ekans",
                    "Poison Blend", 0, MAX_HP, scripts(new boolean[] {true}, new boolean[] {false})),
            new Case("9930829f-33de-5415-8149-936a1a289471", "c07aa937-d483-509a-b4cc-fd1ad2494a28", "Zeraora",
                    "Shocking Knuckle", 20, MAX_HP, scripts(new boolean[] {true}, new boolean[] {false})),
            new Case("aea9e529-8944-5867-abbd-8ea922a0dde4", "3ec76d0e-7d56-5cc5-8e52-eaf3073e2cc1", "MrMime",
                    "Eerie Wave", 20, MAX_HP, none()),
            new Case("88880e3f-80da-5ef0-9db9-41df2e719650", "c8585047-78d2-5d5b-b9af-db3cc12cc1b7", "Trevenant",
                    "Giga Drain", 50, 200, none()));

    /** Everything a player or a test could see after the attack resolves. */
    private record Observed(int defenderHp, int attackerHp, Object defenderConditions, Object poisonCounters,
                            Object coinResults) {}

    private static Observed observe(BatchOneAttacksTest.Fixture f) {
        return new Observed(
                BatchOneAttacksTest.hp(f.defender()),
                BatchOneAttacksTest.hp(f.attacker()),
                f.defender().conditions(),
                f.ops().conditions().poisonCounters.get(f.defender().entityId),
                f.ctx().coinResults);
    }

    @Test
    void generatedRegistersEveryHandPortAttackId() {
        EffectRegistry generated = GeneratedBatchOne.register(EffectRegistry.empty());
        assertEquals(15, CASES.size());
        for (Case c : CASES) {
            assertNotNull(generated.attack(c.abilityId()), c.name() + " " + c.title() + " is registered");
        }
    }

    @Test
    void generatedAttacksBehaveLikeTheHandPort() {
        EffectRegistry hand = BatchOneAttacks.register(EffectRegistry.empty());
        EffectRegistry generated = GeneratedBatchOne.register(EffectRegistry.empty());
        List<String> ran = new ArrayList<>();
        for (Case c : CASES) {
            for (boolean[] script : c.scripts()) {
                String where = c.name() + " " + c.title() + " flips=" + java.util.Arrays.toString(script);
                BatchOneAttacksTest.Fixture h = BatchOneAttacksTest.fixture(hand, c.cardGuid(), c.name(),
                        c.abilityId(), c.title(), c.printed(), c.attackerHp(), script);
                BatchOneAttacksTest.attack(h, c.abilityId());
                h.ops().rng.assertAllUsed();

                BatchOneAttacksTest.Fixture g = BatchOneAttacksTest.fixture(generated, c.cardGuid(), c.name(),
                        c.abilityId(), c.title(), c.printed(), c.attackerHp(), script);
                BatchOneAttacksTest.attack(g, c.abilityId());
                g.ops().rng.assertAllUsed();

                assertEquals(observe(h), observe(g), where);
                ran.add(where);
            }
        }
        assertFalse(ran.isEmpty());
    }
}
