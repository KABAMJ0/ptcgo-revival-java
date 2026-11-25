package com.ptcgo.revival.game.effects;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;
import com.ptcgo.revival.game.wire.Attr;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * Drives each batch-one attack through its registered {@link AttackEffect} with scripted coin flips and checks the
 * damage, heals and Special Conditions against the Python card script it ports.
 */
class BatchOneAttacksTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String A = "00000000-0000-0000-0000-00000000000a";
    private static final String B = "00000000-0000-0000-0000-00000000000b";
    private static final String TARGET_GUID = "dddddddd-0000-4000-8000-000000000001";
    private static final int MAX_HP = 300;
    private static final String GAME_ID = "11111111-1111-1111-1111-111111111111";

    // ---- fixtures --------------------------------------------------------------------------------------------------

    /** Coin outcomes in the order they are flipped; true = heads. Running out or leaving leftovers fails the test. */
    static final class ScriptedRng implements RandomGenerator {
        private final Random ids = new Random(7);
        private final boolean[] script;
        private int next;

        ScriptedRng(boolean... script) {
            this.script = script;
        }

        @Override
        public long nextLong() {
            return ids.nextLong();
        }

        @Override
        public boolean nextBoolean() {
            if (next >= script.length) throw new AssertionError("more coin flips than scripted (" + script.length + ")");
            return script[next++];
        }

        void assertAllUsed() {
            assertEquals(script.length, next, "scripted coin flips not all used");
        }
    }

    /** The slice of the running match the effects touch; the stat and message bookkeeping is not under test. */
    static final class Ops implements SessionOps {
        final Board board;
        final ScriptedRng rng;
        final EffectRegistry registry;
        final ConditionState conditions = new ConditionState();

        Ops(Board board, ScriptedRng rng, EffectRegistry registry) {
            this.board = board;
            this.rng = rng;
            this.registry = registry;
        }

        public String gameId() { return "game"; }
        public Board board() { return board; }
        public TurnStateView turn() {
            return new TurnStateView() {
                public int turnNumber() { return 1; }
                public String activePlayerId() { return A; }
                public boolean supporterPlayed() { return false; }
                public boolean energyAttached() { return false; }
                public boolean retreated() { return false; }
            };
        }
        public RandomGenerator rng() { return rng; }
        public EffectRegistry registry() { return registry; }
        public ConditionState conditions() { return conditions; }
        public void statAdd(String playerId, String key, int amount) {}
        public void statMax(String playerId, String key, int value) {}
        public void creditCardDamage(String playerId, CardEntity attacker, int amount) {}
        public void recordDamage(String targetId, int dealt, boolean byAttack) {}
        public void markHealed(String entityId) {}
        public List<CardEntity> pickEntities(String playerId, String sourceId, List<CardEntity> candidates, int count,
                                             int minimum, String prompt) {
            throw new AssertionError("batch one attacks never pick targets");
        }
        public int maxHp(CardEntity pokemon) { return MAX_HP; }
    }

    /** One attacking Pokemon for A and one Defending Pokemon for B, both at full HP unless the test says otherwise. */
    record Fixture(Ops ops, CardEntity attacker, CardEntity defender, EffectContext ctx) {}

    static Fixture fixture(String cardGuid, String cardName, String abilityId, String title, int printed,
                           int attackerHp, boolean... flips) {
        return fixture(BatchOneAttacks.register(EffectRegistry.empty()), cardGuid, cardName, abilityId, title,
                printed, attackerHp, flips);
    }

    /** Same fixture with the attack resolved through {@code registry} (used by the generated-vs-hand comparison). */
    static Fixture fixture(EffectRegistry registry, String cardGuid, String cardName, String abilityId, String title,
                           int printed, int attackerHp, boolean... flips) {
        ScriptedRng rng = new ScriptedRng(flips);
        Board board = new Board(GAME_ID, List.of(A, B), rng);
        Ops ops = new Ops(board, rng, registry);

        Catalog.Card attackCard = pokemon(cardGuid, cardName, abilityId, title, printed);
        CardEntity attacker = new CardEntity(board.newId(), attackCard, A);
        board.addCardToArea(attacker, board.area(A, "activePokemonArea"));
        attacker.set(Attr.HP, attackerHp);

        CardEntity defender = new CardEntity(board.newId(), pokemon(TARGET_GUID, "Target", null, null, 0), B);
        board.addCardToArea(defender, board.area(B, "activePokemonArea"));

        EffectContext ctx = new EffectContext(ops, A, attacker, abilityId, title, printed, true);
        return new Fixture(ops, attacker, defender, ctx);
    }

    /** Resolves the attack through the registry, exactly as the engine does for a registered attack. */
    static void attack(Fixture f, String abilityId) {
        EffectRegistry.AttackSpec spec = f.ops().registry().attack(abilityId);
        assertNotNull(spec, "attack " + abilityId + " is registered");
        assertNotNull(spec.effect());
        spec.effect().resolve(f.ctx());
    }

    private static Catalog.Card pokemon(String guid, String name, String abilityId, String title, int damage) {
        ObjectNode n = M.createObjectNode();
        put(n, 10140, "json", "{\"id\": \"" + name + "\"}");
        put(n, 10510, "string", "001");
        put(n, 200000, "string", guid);
        put(n, 200300, "int", 0);
        put(n, 200580, "string", "TEST");
        put(n, 200630, "string", name);
        put(n, 200490, "int", MAX_HP);
        put(n, 200540, "int", 0);
        put(n, 200570, "json", "[8]");
        put(n, 200590, "json", "[]");
        put(n, 200600, "int", -1);
        put(n, 200660, "string", "x");
        put(n, 200800, "int", 1);
        put(n, 200820, "int", 2);
        if (abilityId != null) {
            put(n, 200740, "json", "[{\"abilityType\": \"Attack\", \"title\": {\"id\": \"" + title + "\"}, "
                    + "\"gameText\": {\"id\": \"\"}, \"abilityID\": \"" + abilityId + "\", \"cost\": {}, "
                    + "\"damage\": " + damage + ", \"amountOperator\": \"\", \"sortOrder\": 0}]");
        }
        return new Catalog.Card(guid, "TEST", name, "TEST", 1, "pokemon", List.of("Basic"), List.of(), n);
    }

    private static void put(ObjectNode n, int id, String type, Object v) {
        ObjectNode a = n.putObject(String.valueOf(id));
        a.put("type", type);
        if (v instanceof Integer i) a.put("value", i);
        else a.put("value", String.valueOf(v));
    }

    static int hp(CardEntity c) {
        return c.hp();
    }

    // ---- recoil --------------------------------------------------------------------------------------------------

    @Test
    void ironTackleDealsPrintedAndRecoilsTen() {
        String id = "5464af08-f5c9-5d65-9831-0224ac44ae8f";
        Fixture f = fixture("519fd25a-8b28-5552-846b-23f916fbef42", "Beldum", id, "Iron Tackle", 50, MAX_HP);
        attack(f, id);
        assertEquals(250, hp(f.defender()));
        assertEquals(290, hp(f.attacker()));
    }

    @Test
    void wildTackleDealsOneEightyAndRecoilsFifty() {
        String id = "725dfa86-be3e-59d7-a7ff-a5848828aa34";
        Fixture f = fixture("be79dce9-59d1-5f83-9c2a-04ec67e21cab", "Excadrill", id, "Wild Tackle", 180, MAX_HP);
        attack(f, id);
        assertEquals(120, hp(f.defender()));
        assertEquals(250, hp(f.attacker()));
    }

    @Test
    void infernoOnrushDealsOneTwentyAndRecoilsThirty() {
        String id = "156e287f-7d89-5c40-8fc5-f3be6251af7d";
        Fixture f = fixture("eef1253e-f81e-573c-a96c-ba1ebb95fd4f", "Rapidash", id, "Inferno Onrush", 120, MAX_HP);
        attack(f, id);
        assertEquals(180, hp(f.defender()));
        assertEquals(270, hp(f.attacker()));
    }

    @Test
    void recklessChargeDealsThirtyAndRecoilsTen() {
        String id = "68eab2f3-9627-5123-8f9a-2b2c013c4d12";
        Fixture f = fixture("f70536a2-a562-5a49-bb55-e2bc21cf4abf", "Cubone", id, "Reckless Charge", 30, MAX_HP);
        attack(f, id);
        assertEquals(270, hp(f.defender()));
        assertEquals(290, hp(f.attacker()));
    }

    @Test
    void turbulentWingsDealsOneFiftyAndRecoilsFifty() {
        String id = "387cdf26-00b5-59b0-a589-ba33ee7c5a2a";
        Fixture f = fixture("3079e20d-cc24-50cf-99f9-43c85db38275", "Volcarona", id, "Turbulent Wings", 150, MAX_HP);
        attack(f, id);
        assertEquals(150, hp(f.defender()));
        assertEquals(250, hp(f.attacker()));
    }

    // ---- flips ---------------------------------------------------------------------------------------------------

    @Test
    void spikeCannonDealsThirtyPerHeadsOfThreeFlips() {
        String id = "efe1bd3a-9187-5bd4-a50b-ba7302f60350";
        Fixture f = fixture("b1757be8-97f5-5326-8a72-dad91d0544ef", "Forretress", id, "Spike Cannon", 30, MAX_HP,
                true, false, true);
        attack(f, id);
        f.ops().rng.assertAllUsed();
        assertEquals(List.of(true, false, true), f.ctx().coinResults);
        assertEquals(240, hp(f.defender()), "two heads x 30");
        assertEquals(MAX_HP, hp(f.attacker()), "no recoil on Spike Cannon");
    }

    @Test
    void doubleHitDealsNinetyPerHeadsOfTwoFlipsAndNothingOnTails() {
        String id = "0a04ce8a-e3eb-5c76-9f28-c59da0654271";
        Fixture heads = fixture("bde4995f-40fb-549b-8ba5-867874badde8", "Palafin", id, "Double Hit", 90, MAX_HP,
                true, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(120, hp(heads.defender()), "two heads x 90");

        Fixture tails = fixture("bde4995f-40fb-549b-8ba5-867874badde8", "Palafin", id, "Double Hit", 90, MAX_HP,
                false, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(MAX_HP, hp(tails.defender()), "no heads, no damage");
    }

    @Test
    void tonguenadoDealsSeventyPerHeadsOfFourFlips() {
        String id = "3ba2bf0b-97e1-54ff-8b45-a8d1801941dd";
        Fixture f = fixture("cc211d81-798e-5687-9f55-f4bf7023e41e", "Lickilicky", id, "Tonguenado", 70, MAX_HP,
                true, false, true, false);
        attack(f, id);
        f.ops().rng.assertAllUsed();
        assertEquals(160, hp(f.defender()), "two heads x 70");
    }

    @Test
    void knockAwayDealsTwentyPlusFortyOnHeads() {
        String id = "efd41a84-d71d-5233-97f1-0de344278de2";
        Fixture heads = fixture("fdaca5d0-bc3c-5312-bac2-ddafa86a0f00", "Carkol", id, "Knock Away", 20, MAX_HP, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(240, hp(heads.defender()), "heads: 20 + 40");

        Fixture tails = fixture("fdaca5d0-bc3c-5312-bac2-ddafa86a0f00", "Carkol", id, "Knock Away", 20, MAX_HP, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(280, hp(tails.defender()), "tails: printed 20 only");
    }

    @Test
    void quickAttackDealsTwentyPlusTwentyOnHeads() {
        String id = "87e1ad08-ffba-5e19-a7fe-b3714a9ac564";
        Fixture heads = fixture("82032ee6-c872-5cee-96d6-e4bea56ae320", "Eevee", id, "Quick Attack", 20, MAX_HP, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(260, hp(heads.defender()), "heads: 20 + 20");

        Fixture tails = fixture("82032ee6-c872-5cee-96d6-e4bea56ae320", "Eevee", id, "Quick Attack", 20, MAX_HP, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(280, hp(tails.defender()), "tails: printed 20 only");
    }

    // ---- Special Conditions --------------------------------------------------------------------------------------

    @Test
    void panicPoisonBurnsConfusesAndPoisonsWithoutDamage() {
        String id = "6b19ed4c-8699-5429-955d-750e30bc5506";
        Fixture f = fixture("cc02c67b-528b-5f9d-ab0e-ff4e4e70bd5d", "Arbok", id, "Panic Poison", 0, MAX_HP);
        attack(f, id);
        assertEquals(MAX_HP, hp(f.defender()));
        assertEquals(List.of("Burned", "Confused", "Poisoned"), f.defender().conditions());
        assertEquals(1, f.ops().conditions().poisonCounters.get(f.defender().entityId));
    }

    @Test
    void poisonBlendConfusesAndPoisonsOnHeadsOnly() {
        String id = "12c0dc7a-50ee-503d-b085-8f5aa3daa91f";
        Fixture heads = fixture("81e3fe26-5ed8-5733-bb0e-d63bc5c1f52b", "Ekans", id, "Poison Blend", 0, MAX_HP, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(List.of("Confused", "Poisoned"), heads.defender().conditions());

        Fixture tails = fixture("81e3fe26-5ed8-5733-bb0e-d63bc5c1f52b", "Ekans", id, "Poison Blend", 0, MAX_HP, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(List.of(), tails.defender().conditions());
    }

    @Test
    void shockingKnuckleDealsTwentyAndParalyzesOnHeads() {
        String id = "9930829f-33de-5415-8149-936a1a289471";
        Fixture heads = fixture("c07aa937-d483-509a-b4cc-fd1ad2494a28", "Zeraora", id, "Shocking Knuckle", 20, MAX_HP, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(280, hp(heads.defender()));
        assertEquals(List.of("Paralyzed"), heads.defender().conditions());

        Fixture tails = fixture("c07aa937-d483-509a-b4cc-fd1ad2494a28", "Zeraora", id, "Shocking Knuckle", 20, MAX_HP, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(280, hp(tails.defender()));
        assertEquals(List.of(), tails.defender().conditions());
    }

    @Test
    void eerieWaveDealsTwentyAndConfuses() {
        String id = "aea9e529-8944-5867-abbd-8ea922a0dde4";
        Fixture f = fixture("3ec76d0e-7d56-5cc5-8e52-eaf3073e2cc1", "MrMime", id, "Eerie Wave", 20, MAX_HP);
        attack(f, id);
        assertEquals(280, hp(f.defender()));
        assertEquals(List.of("Confused"), f.defender().conditions());
    }

    // ---- heal ----------------------------------------------------------------------------------------------------

    @Test
    void gigaDrainHealsTheAttackerForTheDamageDealt() {
        String id = "88880e3f-80da-5ef0-9db9-41df2e719650";
        Fixture f = fixture("c8585047-78d2-5d5b-b9af-db3cc12cc1b7", "Trevenant", id, "Giga Drain", 50, 200);
        attack(f, id);
        assertEquals(250, hp(f.defender()));
        assertEquals(250, hp(f.attacker()), "healed by the 50 dealt");
    }

    // ---- registry ------------------------------------------------------------------------------------------------

    @Test
    void batchRegistersFifteenAttacks() {
        EffectRegistry r = BatchOneAttacks.register(EffectRegistry.empty());
        String[] ids = {
            "5464af08-f5c9-5d65-9831-0224ac44ae8f", "725dfa86-be3e-59d7-a7ff-a5848828aa34",
            "156e287f-7d89-5c40-8fc5-f3be6251af7d", "68eab2f3-9627-5123-8f9a-2b2c013c4d12",
            "387cdf26-00b5-59b0-a589-ba33ee7c5a2a", "efe1bd3a-9187-5bd4-a50b-ba7302f60350",
            "0a04ce8a-e3eb-5c76-9f28-c59da0654271", "3ba2bf0b-97e1-54ff-8b45-a8d1801941dd",
            "efd41a84-d71d-5233-97f1-0de344278de2", "87e1ad08-ffba-5e19-a7fe-b3714a9ac564",
            "6b19ed4c-8699-5429-955d-750e30bc5506", "12c0dc7a-50ee-503d-b085-8f5aa3daa91f",
            "9930829f-33de-5415-8149-936a1a289471", "aea9e529-8944-5867-abbd-8ea922a0dde4",
            "88880e3f-80da-5ef0-9db9-41df2e719650",
        };
        assertEquals(15, ids.length);
        for (String id : ids) assertNotNull(r.attack(id), id);
    }
}
