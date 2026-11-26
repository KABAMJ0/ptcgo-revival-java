package com.ptcgo.revival.game.effects;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;
import com.ptcgo.revival.game.wire.Attr;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * SWSH1 attacks resolved through the registry with scripted coins, checked against the numbers the Python card
 * scripts produce for the same board.
 */
class Swsh1AttacksTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String A = "00000000-0000-0000-0000-00000000000a";
    private static final String B = "00000000-0000-0000-0000-00000000000b";
    private static final int MAX_HP = 300;

    /** The session slice the effects touch, recording retreat locks. */
    static final class Ops implements SessionOps {
        final Board board;
        final BatchTwoAttacksTest.ScriptedRng rng;
        final EffectRegistry registry = Swsh1Attacks.register(EffectRegistry.empty());
        final ConditionState conditions = new ConditionState();
        final List<String> retreatLocked = new ArrayList<>();

        Ops(Board board, BatchTwoAttacksTest.ScriptedRng rng) {
            this.board = board;
            this.rng = rng;
        }

        public String gameId() { return "game"; }
        public Board board() { return board; }
        public TurnStateView turn() {
            return new TurnStateView() {
                public int turnNumber() { return 3; }
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
        public List<CardEntity> pickEntities(String p, String s, List<CardEntity> c, int n, int min, String prompt) {
            throw new AssertionError("no SWSH1 phase-one attack picks targets");
        }
        public int maxHp(CardEntity pokemon) { return MAX_HP; }
        @Override public void lockRetreat(CardEntity target) { retreatLocked.add(target.entityId); }
    }

    /** A board: attacker (A's Active) and defender (B's Active), both 300 HP, no Weakness or Resistance. */
    static final class Game {
        final Board board;
        final Ops ops;
        final CardEntity attacker;
        final CardEntity defender;
        final String cardGuid;
        final int slot;

        Game(String cardGuid, int slot, String name, int printed, boolean... flips) {
            this(cardGuid, slot, name, List.of("Basic"), flips);
        }

        Game(String cardGuid, int slot, String name, List<String> defenderSubtypes, boolean... flips) {
            BatchTwoAttacksTest.ScriptedRng rng = new BatchTwoAttacksTest.ScriptedRng(flips);
            board = new Board("11111111-1111-1111-1111-111111111111", List.of(A, B), rng);
            ops = new Ops(board, rng);
            this.cardGuid = cardGuid;
            this.slot = slot;
            attacker = place(A, "activePokemonArea", pokemon(cardGuid, name, List.of("Basic"), 1));
            defender = place(B, "activePokemonArea", pokemon("dddddddd-0000-4000-8000-000000000001", "Target", defenderSubtypes, 1));
        }

        CardEntity place(String owner, String area, Catalog.Card card) {
            CardEntity c = new CardEntity(board.newId(), card, owner);
            board.addCardToArea(c, board.area(owner, area));
            return c;
        }

        CardEntity bench(String owner, String logicName) {
            return place(owner, "bench", pokemon("eeeeeeee-0000-4000-8000-" + String.format("%012d", board.newId().hashCode() & 0xffffff),
                    logicName, List.of("Basic"), 1));
        }

        void energy(CardEntity pokemon, int type) {
            CardEntity e = place(pokemon.owningPlayerId, "hand", energyCard(type));
            assertTrue(board.attachCard(e.entityId, pokemon.entityId));
        }

        int resolve(int printed) {
            String id = Swsh1Attacks.abilityId(cardGuid, slot);
            EffectRegistry.AttackSpec spec = ops.registry().attack(id);
            assertNotNull(spec, "attack registered: " + id);
            EffectContext ctx = new EffectContext(ops, A, attacker, id, "Test", printed, true);
            spec.effect().resolve(ctx);
            ops.rng.assertAllUsed();
            return MAX_HP - defender.hp();
        }
    }

    static Catalog.Card pokemon(String guid, String logicName, List<String> subtypes, int retreatCost) {
        ObjectNode n = M.createObjectNode();
        put(n, 10140, "json", "{\"id\": \"" + logicName + "\"}");
        put(n, 200000, "string", guid);
        put(n, 200300, "int", Attr.CT_POKEMON);
        put(n, 200630, "string", logicName);
        put(n, 200490, "int", MAX_HP);
        put(n, 200540, "int", 0);
        put(n, 200570, "json", "[1]");
        put(n, 200590, "json", "[]");
        put(n, 200600, "int", -1);
        put(n, 200800, "int", retreatCost);
        return new Catalog.Card(guid, "TEST", logicName, "TEST", 1, "pokemon", subtypes, List.of(), n);
    }

    static Catalog.Card energyCard(int type) {
        ObjectNode n = M.createObjectNode();
        put(n, 10140, "json", "{\"id\": \"Energy\"}");
        put(n, 200300, "int", Attr.CT_ENERGY);
        put(n, 200570, "json", "[" + type + "]");
        put(n, 201040, "json", "{\"options\": [[" + type + "]]}");
        return new Catalog.Card("ffffffff-0000-4000-8000-00000000000" + type % 10, "TEST", "Energy", "TEST", 1, "energy",
                List.of("Basic"), List.of(), n);
    }

    private static void put(ObjectNode n, int id, String type, Object v) {
        ObjectNode a = n.putObject(String.valueOf(id));
        a.put("type", type);
        if (v instanceof Integer i) a.put("value", i);
        else a.put("value", String.valueOf(v));
    }

    private static final int FIRE = 6, WATER = 11;

    // ---- ids and registration ----------------------------------------------------------------------------------

    @Test
    void abilityIdsMatchPythonDerivation() {
        // Values printed by uuid.uuid5(_ABILITY_ID_NAMESPACE, f"{guid}:ability:{slot}") for these SWSH1 cards.
        assertEquals("a68c4f0c-9ec9-50b0-8676-f8443d3b0a4a", Swsh1Attacks.abilityId("5ab3accb-bf6e-526f-85c9-fd65413f0e64", 1));
        assertEquals("d70ec01c-23ee-5401-849b-006e387d33f0", Swsh1Attacks.abilityId("b7fce58a-eed8-5d50-91ad-056c9af03b78", 0));
        assertEquals("33c47e85-1b42-5c38-b6a2-2fb8fcbd1c6f", Swsh1Attacks.abilityId("df8394b8-de15-51b8-92e0-7f3740ea024d", 1));
    }

    @Test
    void noSwsh1AttackOverridesAnEarlierRegistration() {
        EffectRegistry earlier = EffectRegistry.empty();
        BatchOneAttacks.register(earlier);
        BatchTwoAttacks.register(earlier);
        GeneratedStandardBatch.register(earlier);
        GeneratedStandardBatch2.register(earlier);
        Set<String> swsh1 = new HashSet<>(Swsh1Attacks.register(EffectRegistry.empty()).attackIds());
        assertEquals(41, swsh1.size(), "41 distinct SWSH1 attack ids");
        swsh1.retainAll(earlier.attackIds());
        assertTrue(swsh1.isEmpty(), "collisions: " + swsh1);
    }

    // ---- damage_per ----------------------------------------------------------------------------------------------

    @Test
    void lineForceAddsTwentyPerBenchedPokemon() {
        Game g = new Game("93c4af72-ab2c-566a-871f-9834d9db7e9c", 1, "CelebiV", 50);
        g.bench(A, "Grookey");
        g.bench(A, "Grookey");
        g.bench(A, "Scorbunny");
        g.bench(B, "Ignored");
        assertEquals(50 + 3 * 20, g.resolve(50));
    }

    @Test
    void chargeOrderCountsOnlyPawniard() {
        Game g = new Game("e39ed81f-f4ec-5489-b7bc-4890749fa45e", 0, "Bisharp", 30);
        g.bench(A, "Pawniard");
        g.bench(A, "Pawniard");
        g.bench(A, "Bisharp");
        assertEquals(30 + 2 * 30, g.resolve(30));
    }

    @Test
    void secretSwordCountsOnlyWaterEnergy() {
        Game g = new Game("b27685d3-902a-5039-8132-7ca8811267d0", 1, "KeldeoV", 50);
        g.energy(g.attacker, WATER);
        g.energy(g.attacker, WATER);
        g.energy(g.attacker, FIRE);
        assertEquals(50 + 2 * 30, g.resolve(50));
    }

    @Test
    void psychicCountsDefenderEnergy() {
        Game g = new Game("ce15658e-4699-5663-978e-87cd51033118", 1, "GalarianRapidash", 30);
        g.energy(g.defender, FIRE);
        g.energy(g.defender, WATER);
        assertEquals(30 + 2 * 30, g.resolve(30));
    }

    @Test
    void ragingHammerCountsOwnDamageCounters() {
        Game g = new Game("9f385d35-3e03-5e6f-a1e2-eb3802ff0fb6", 0, "RegirockV", 30);
        g.attacker.set(Attr.HP, MAX_HP - 50);
        assertEquals(30 + 5 * 10, g.resolve(30));
    }

    @Test
    void crazyClawsCountsDefenderDamageCounters() {
        Game g = new Game("59eb1d89-ad28-5923-8d84-e867c63358cf", 1, "SableyeV", 10);
        g.defender.set(Attr.HP, MAX_HP - 20);
        // 2 counters already on it: 10 + 2 * 60 = 130 more; resolve() reports total damage on the defender.
        assertEquals(20 + 130, g.resolve(10));
    }

    @Test
    void viseBiteUsesPrintedRetreatCost() {
        Game g = new Game("c5f3a167-6b62-513b-9fde-d8e4e304c7b2", 0, "Drednaw", 60);
        assertEquals(60 + 1 * 30, g.resolve(60));
    }

    @Test
    void energyBurstCountsBothActives() {
        Game g = new Game("3b65f1a3-5299-5b0e-8a5b-524bf177645b", 1, "VictiniV", 30);
        g.energy(g.attacker, FIRE);
        g.energy(g.defender, WATER);
        g.energy(g.defender, WATER);
        assertEquals(3 * 30, g.resolve(30));
    }

    @Test
    void flailWithNoDamageDoesNothing() {
        Game g = new Game("f5463868-6cf5-58e5-9d26-ef04c5886e49", 1, "Sudowoodo", 10);
        assertEquals(0, g.resolve(10));
    }

    // ---- bonus_if ------------------------------------------------------------------------------------------------

    @Test
    void muscularNoseDoesNothingFromEightCounters() {
        Game g = new Game("88bf3243-6420-5365-9449-6de59cd6dbbf", 1, "Copperajah", 220);
        g.attacker.set(Attr.HP, MAX_HP - 80);
        assertEquals(0, g.resolve(220));
        Game h = new Game("88bf3243-6420-5365-9449-6de59cd6dbbf", 1, "Copperajah", 220);
        h.attacker.set(Attr.HP, MAX_HP - 70);
        assertEquals(220, h.resolve(220));
    }

    @Test
    void coordinatedBeatdownNeedsHitmonlee() {
        Game g = new Game("18ac4b65-9532-5b79-abfb-4122e9a3a7ae", 0, "Hitmonchan", 20);
        assertEquals(20, g.resolve(20));
        Game h = new Game("18ac4b65-9532-5b79-abfb-4122e9a3a7ae", 0, "Hitmonchan", 20);
        h.bench(A, "Hitmonlee");
        assertEquals(40, h.resolve(20));
    }

    @Test
    void fightingFangsBonusVersusPokemonV() {
        Game g = new Game("de957e31-726c-5a09-9d74-19f7a364a5d9", 1, "Boltund", 90);
        assertEquals(90, g.resolve(90));
        Game h = new Game("de957e31-726c-5a09-9d74-19f7a364a5d9", 1, "Boltund", List.of("Basic", "V"));
        assertEquals(180, h.resolve(90));
        Game x = new Game("de957e31-726c-5a09-9d74-19f7a364a5d9", 1, "Boltund", List.of("Basic", "GX"));
        assertEquals(180, x.resolve(90));
    }

    @Test
    void superHypnoblastBonusOnAsleepDefender() {
        Game g = new Game("bb1f1731-14bd-59fd-8657-a7fb347b2b2b", 1, "Musharna", 10);
        g.defender.setConditions(new ArrayList<>(List.of(Attr.ASLEEP)));
        assertEquals(130, g.resolve(10));
    }

    @Test
    void powerPressNeedsThreeFightingEnergy() {
        Game g = new Game("e820b680-0fd1-5cbc-bcf2-f509b9950590", 1, "Sandaconda", 60);
        for (int i = 0; i < 3; i++) g.energy(g.attacker, 5);
        assertEquals(130, g.resolve(60));
    }

    // ---- coin flips ----------------------------------------------------------------------------------------------

    @Test
    void damageRushFlipsUntilTails() {
        Game g = new Game("59f91582-85c7-57c7-9fa5-7bc37ce7e13b", 1, "GalarianStunfisk", 30, true, true, false);
        assertEquals(30 + 2 * 30, g.resolve(30));
    }

    @Test
    void relentlessFlamesFirstTailsDoesNothing() {
        Game g = new Game("5a8b1d66-0a2c-5f15-ac88-f3285e2b93a9", 0, "Sizzlipede", 40, false);
        assertEquals(0, g.resolve(40));
    }

    @Test
    void powerfulNeedlesFlipsOnePerEnergy() {
        Game g = new Game("91548e48-5444-54ea-b2d2-d7a6eb2229a3", 1, "Maractus", 60, true, false);
        g.energy(g.attacker, 7);
        g.energy(g.attacker, 7);
        assertEquals(60, g.resolve(60));
    }

    // ---- condition options ---------------------------------------------------------------------------------------

    @Test
    void paralyzePoisonAlwaysPoisonsParalyzesOnHeads() {
        Game g = new Game("683015b9-fae8-5341-aa5d-3c74a1e92f52", 0, "Roserade", 30, false);
        assertEquals(30, g.resolve(30));
        assertEquals(List.of(Attr.POISONED), g.defender.conditions());
        Game h = new Game("683015b9-fae8-5341-aa5d-3c74a1e92f52", 0, "Roserade", 30, true);
        h.resolve(30);
        assertTrue(h.defender.conditions().containsAll(List.of(Attr.POISONED, Attr.PARALYZED)));
    }

    @Test
    void bigBiteLocksDefenderRetreat() {
        Game g = new Game("de957e31-726c-5a09-9d74-19f7a364a5d9", 0, "Boltund", 50);
        assertEquals(50, g.resolve(50));
        assertEquals(List.of(g.defender.entityId), g.ops.retreatLocked);
    }

    @Test
    void fallingDownPutsTheAttackerToSleep() {
        Game g = new Game("0782209a-a452-56a2-a139-627e4076a465", 1, "SnorlaxV", 170);
        assertEquals(170, g.resolve(170));
        assertEquals(List.of(Attr.ASLEEP), g.attacker.conditions());
        assertTrue(g.defender.conditions().isEmpty());
    }

    @Test
    void sleepyPulseFlipsTwoCoinsToWake() {
        Game g = new Game("bb1f1731-14bd-59fd-8657-a7fb347b2b2b", 0, "Musharna", 0);
        assertEquals(0, g.resolve(0));
        assertEquals(List.of(Attr.ASLEEP), g.defender.conditions());
        assertEquals(2, g.ops.conditions.sleepCheckupCoins.get(g.defender.entityId));
    }
}
