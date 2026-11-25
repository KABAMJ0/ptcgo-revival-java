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
 * Drives each batch-two attack through its registered {@link AttackEffect} with scripted coin flips and checks the
 * damage, heals and Special Conditions against the Python card script it ports.
 */
class BatchTwoAttacksTest {
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
            throw new AssertionError("batch two attacks never pick targets");
        }
        public int maxHp(CardEntity pokemon) { return MAX_HP; }
    }

    /** One attacking Pokemon for A and one Defending Pokemon for B, both at full HP unless the test says otherwise. */
    record Fixture(Ops ops, CardEntity attacker, CardEntity defender, EffectContext ctx) {}

    static Fixture fixture(String cardGuid, String cardName, String abilityId, String title, int printed,
                                   int attackerHp, boolean... flips) {
        ScriptedRng rng = new ScriptedRng(flips);
        Board board = new Board(GAME_ID, List.of(A, B), rng);
        EffectRegistry registry = BatchTwoAttacks.register(EffectRegistry.empty());
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
    void heatDiveDealsThirtyAndRecoilsTen() {
        String id = "11fe8b65-3f81-5465-8300-1b2e8ef2bf1e";
        Fixture f = fixture("c3fda087-8c0e-5389-b8c8-ab1f1ceda0e5", "Sizzlipede", id, "Heat Dive", 30, MAX_HP);
        attack(f, id);
        assertEquals(270, hp(f.defender()));
        assertEquals(290, hp(f.attacker()));
    }

    @Test
    void doubleEdgeDealsTwentyAndRecoilsTen() {
        String id = "d29de89d-3d43-5c0d-a7df-cf6009e91f7e";
        Fixture f = fixture("0a07b8c8-1329-5deb-b3d2-c70295a7017b", "Aron", id, "Double-Edge", 20, MAX_HP);
        attack(f, id);
        assertEquals(280, hp(f.defender()));
        assertEquals(290, hp(f.attacker()));
    }

    @Test
    void ironTackleKlangDealsSixtyAndRecoilsTwenty() {
        String id = "0c3c7cce-51ab-5300-854c-6320e9232c79";
        Fixture f = fixture("7f9d9a92-41ae-5ca9-855f-c5ebcb273ff8", "Klang", id, "Iron Tackle", 60, MAX_HP);
        attack(f, id);
        assertEquals(240, hp(f.defender()));
        assertEquals(280, hp(f.attacker()));
    }

    @Test
    void woodHammerDealsOneEightyAndRecoilsFifty() {
        String id = "79cf510d-aab3-5ae5-a5e6-bb27cf97678f";
        Fixture f = fixture("50971bb7-1e0f-50be-8809-a5b9957cfaab", "Rillaboom", id, "Wood Hammer", 180, MAX_HP);
        attack(f, id);
        assertEquals(120, hp(f.defender()));
        assertEquals(250, hp(f.attacker()));
    }

    @Test
    void braveBirdDealsTwoHundredAndRecoilsThirty() {
        String id = "17cfcf34-e7a3-5a18-b4a3-678289075bc9";
        Fixture f = fixture("ca79a578-7a16-5a69-a96c-131054d0e7d7", "Flamigo ex", id, "Brave Bird", 200, MAX_HP);
        attack(f, id);
        assertEquals(100, hp(f.defender()));
        assertEquals(270, hp(f.attacker()));
    }

    // ---- flips per heads -----------------------------------------------------------------------------------------

    @Test
    void cometSlapDealsThirtyPerHeadsOfThreeFlips() {
        String id = "895f5a3f-1ab5-583e-8d5d-787c2378faa0";
        Fixture f = fixture("ed5bcbd2-c7fb-5189-ad3f-07a9d066f735", "Nuzleaf", id, "Comet Slap", 30, MAX_HP,
                true, false, true);
        attack(f, id);
        f.ops().rng.assertAllUsed();
        assertEquals(List.of(true, false, true), f.ctx().coinResults);
        assertEquals(240, hp(f.defender()), "two heads x 30");
    }

    @Test
    void doubleTrickDealsThirtyPerHeadsOfTwoFlipsAndNothingOnTails() {
        String id = "326fbeca-1015-5241-aa4d-d21c211494dd";
        Fixture heads = fixture("bbf30100-f6b1-535a-9882-dd6fd87b5a64", "Duosion", id, "Double Trick", 30, MAX_HP,
                true, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(240, hp(heads.defender()), "two heads x 30");

        Fixture tails = fixture("bbf30100-f6b1-535a-9882-dd6fd87b5a64", "Duosion", id, "Double Trick", 30, MAX_HP,
                false, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(MAX_HP, hp(tails.defender()), "no heads, no damage");
    }

    @Test
    void tailRapDealsTwentyPerHeadsOfTwoFlips() {
        String id = "4258bdba-90d0-5df0-a57a-94bd1e21564a";
        Fixture f = fixture("e3b48006-a3a5-5896-81fd-72b3c7868cd0", "Poliwag", id, "Tail Rap", 20, MAX_HP,
                true, false);
        attack(f, id);
        f.ops().rng.assertAllUsed();
        assertEquals(280, hp(f.defender()), "one heads x 20");
    }

    @Test
    void ironSwingDealsHundredPerHeadsOfTwoFlips() {
        String id = "ca052cd6-6eec-5758-8a16-3a6569cc0871";
        Fixture f = fixture("de4c7f86-77f0-5372-814e-93d54fd53eab", "Melmetal ex", id, "Iron Swing", 100, MAX_HP,
                true, true);
        attack(f, id);
        f.ops().rng.assertAllUsed();
        assertEquals(100, hp(f.defender()), "two heads x 100");
    }

    @Test
    void incessantIncisorsDealsThirtyPerHeadsOfFourFlips() {
        String id = "b97a7dae-fa0c-5f3a-bd89-bd26ea946855";
        Fixture f = fixture("e43b03b3-9a24-5bea-a453-6919336800c8", "Maushold", id, "Incessant Incisors", 30, MAX_HP,
                true, true, false, true);
        attack(f, id);
        f.ops().rng.assertAllUsed();
        assertEquals(210, hp(f.defender()), "three heads x 30");
    }

    // ---- flips with a bonus --------------------------------------------------------------------------------------

    @Test
    void playRoughAddsThirtyOnHeads() {
        String id = "b9537570-1f56-5385-9a60-fd4e062a7644";
        Fixture heads = fixture("57de94bf-36f5-5171-aebd-880b090d7c0f", "Azumarill", id, "Play Rough", 30, MAX_HP, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(240, hp(heads.defender()), "heads: 30 + 30");

        Fixture tails = fixture("57de94bf-36f5-5171-aebd-880b090d7c0f", "Azumarill", id, "Play Rough", 30, MAX_HP, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(270, hp(tails.defender()), "tails: printed 30 only");
    }

    @Test
    void shiftingShurikenAddsHundredOnHeads() {
        String id = "67235615-c2bc-5e0b-8091-3a28a8f1fe6e";
        Fixture heads = fixture("1ed7f7cd-9409-58cd-b732-0013c84d599b", "Greninja ex", id, "Shifting Shuriken", 100,
                MAX_HP, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(100, hp(heads.defender()), "heads: 100 + 100");

        Fixture tails = fixture("1ed7f7cd-9409-58cd-b732-0013c84d599b", "Greninja ex", id, "Shifting Shuriken", 100,
                MAX_HP, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(200, hp(tails.defender()), "tails: printed 100 only");
    }

    @Test
    void burningRunAddsSixtyOnHeads() {
        String id = "d4c9d628-f5a8-51e8-9cf4-1ffe9fe24a35";
        Fixture heads = fixture("ef9ff7bc-1573-54e9-a95d-ddc792b27c2d", "Rapidash", id, "Burning Run", 60, MAX_HP, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(180, hp(heads.defender()), "heads: 60 + 60");

        Fixture tails = fixture("ef9ff7bc-1573-54e9-a95d-ddc792b27c2d", "Rapidash", id, "Burning Run", 60, MAX_HP, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(240, hp(tails.defender()), "tails: printed 60 only");
    }

    @Test
    void heatBreathAddsFiftyOnHeads() {
        String id = "ed6e3d1b-0e3e-56cb-9b98-ca101097591e";
        Fixture heads = fixture("b21a4db6-7333-5899-b187-37d652536187", "Crocalor", id, "Heat Breath", 30, MAX_HP, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(220, hp(heads.defender()), "heads: 30 + 50");

        Fixture tails = fixture("b21a4db6-7333-5899-b187-37d652536187", "Crocalor", id, "Heat Breath", 30, MAX_HP, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(270, hp(tails.defender()), "tails: printed 30 only");
    }

    // ---- Special Conditions --------------------------------------------------------------------------------------

    @Test
    void tailTrickeryDealsThirtyAndConfuses() {
        String id = "0a110880-5713-5821-b8ec-f04b901479e1";
        Fixture f = fixture("d96b3bf9-6f39-5acb-88ff-de44e8b5d5cd", "Delcatty", id, "Tail Trickery", 30, MAX_HP);
        attack(f, id);
        assertEquals(270, hp(f.defender()));
        assertEquals(List.of("Confused"), f.defender().conditions());
    }

    @Test
    void powderSnowDealsTenAndPutsDefenderToSleep() {
        String id = "2d22c471-d83b-5431-bce6-b2f8bcc95672";
        Fixture f = fixture("02e8594c-abf9-5f75-9191-3d731a2739c4", "Snom", id, "Powder Snow", 10, MAX_HP);
        attack(f, id);
        assertEquals(290, hp(f.defender()));
        assertEquals(List.of("Asleep"), f.defender().conditions());
    }

    @Test
    void thunderShockParalyzesOnHeadsOnly() {
        String id = "52ca1315-f673-5dd8-a100-13eeaace8065";
        Fixture heads = fixture("8ce9e93c-f5bf-5aee-92e2-04c622259947", "Bellibolt", id, "Thunder Shock", 50, MAX_HP, true);
        attack(heads, id);
        heads.ops().rng.assertAllUsed();
        assertEquals(250, hp(heads.defender()));
        assertEquals(List.of("Paralyzed"), heads.defender().conditions());

        Fixture tails = fixture("8ce9e93c-f5bf-5aee-92e2-04c622259947", "Bellibolt", id, "Thunder Shock", 50, MAX_HP, false);
        attack(tails, id);
        tails.ops().rng.assertAllUsed();
        assertEquals(250, hp(tails.defender()), "damage is printed either way");
        assertEquals(List.of(), tails.defender().conditions());
    }

    @Test
    void dangerousToxwhipDealsOneFiftyAndConfusesAndPoisons() {
        String id = "679b047d-bbf9-5f2e-92e6-dd99d7be33f5";
        Fixture f = fixture("a0950f61-ea0f-527d-bb89-20f24e2666b3", "Venusaur ex", id, "Dangerous Toxwhip", 150, MAX_HP);
        attack(f, id);
        assertEquals(150, hp(f.defender()));
        assertEquals(List.of("Confused", "Poisoned"), f.defender().conditions());
        assertEquals(1, f.ops().conditions().poisonCounters.get(f.defender().entityId));
    }

    @Test
    void searingFlameDealsTwentyAndBurns() {
        String id = "f7d9c2b4-930e-52a7-a7a0-e4df3b5e8ccf";
        Fixture f = fixture("e9b198e2-c9ff-52b1-b3d3-a1e3c31c1bc2", "Pansear", id, "Searing Flame", 20, MAX_HP);
        attack(f, id);
        assertEquals(280, hp(f.defender()));
        assertEquals(List.of("Burned"), f.defender().conditions());
    }

    // ---- heal ----------------------------------------------------------------------------------------------------

    @Test
    void suctionHealsTheAttackerForTheDamageDealt() {
        String id = "f8f3015c-ca38-561c-96c4-ecae84915603";
        Fixture f = fixture("4513cdbb-3905-5083-b86c-62d5da2e411e", "Iron Moth", id, "Suction", 30, 200);
        attack(f, id);
        assertEquals(270, hp(f.defender()));
        assertEquals(230, hp(f.attacker()), "healed by the 30 dealt");
    }

    // ---- registry ------------------------------------------------------------------------------------------------

    @Test
    void batchRegistersTwentyAttacks() {
        EffectRegistry r = BatchTwoAttacks.register(EffectRegistry.empty());
        String[] ids = {
            "11fe8b65-3f81-5465-8300-1b2e8ef2bf1e", "d29de89d-3d43-5c0d-a7df-cf6009e91f7e",
            "0c3c7cce-51ab-5300-854c-6320e9232c79", "79cf510d-aab3-5ae5-a5e6-bb27cf97678f",
            "17cfcf34-e7a3-5a18-b4a3-678289075bc9", "895f5a3f-1ab5-583e-8d5d-787c2378faa0",
            "326fbeca-1015-5241-aa4d-d21c211494dd", "4258bdba-90d0-5df0-a57a-94bd1e21564a",
            "ca052cd6-6eec-5758-8a16-3a6569cc0871", "b97a7dae-fa0c-5f3a-bd89-bd26ea946855",
            "b9537570-1f56-5385-9a60-fd4e062a7644", "67235615-c2bc-5e0b-8091-3a28a8f1fe6e",
            "d4c9d628-f5a8-51e8-9cf4-1ffe9fe24a35", "ed6e3d1b-0e3e-56cb-9b98-ca101097591e",
            "0a110880-5713-5821-b8ec-f04b901479e1", "2d22c471-d83b-5431-bce6-b2f8bcc95672",
            "52ca1315-f673-5dd8-a100-13eeaace8065", "679b047d-bbf9-5f2e-92e6-dd99d7be33f5",
            "f7d9c2b4-930e-52a7-a7a0-e4df3b5e8ccf", "f8f3015c-ca38-561c-96c4-ecae84915603",
        };
        assertEquals(20, ids.length);
        for (String id : ids) assertNotNull(r.attack(id), id);
    }
}
