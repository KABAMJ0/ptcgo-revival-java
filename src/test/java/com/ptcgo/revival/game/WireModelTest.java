package com.ptcgo.revival.game;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.game.effects.DamageCalc;
import com.ptcgo.revival.game.engine.LegalActions;
import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;
import com.ptcgo.revival.game.model.PlayArea;
import com.ptcgo.revival.game.wire.Attr;
import com.ptcgo.revival.game.wire.GameSequence;
import com.ptcgo.revival.game.wire.Msgs;
import com.ptcgo.revival.net.WargCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Packet shapes and the board model (M1). */
class WireModelTest {
    private static Board board(long seed) {
        Board b = new Board(GameFixtures.GAME, List.of(GameFixtures.A, GameFixtures.B), new Random(seed));
        b.populateDeck(GameFixtures.A, TestCatalog.lightningDeck(), TestCatalog.catalog());
        b.populateDeck(GameFixtures.B, TestCatalog.lightningDeck(), TestCatalog.catalog());
        return b;
    }

    private static JsonNode json(Object o) {
        return WargCodec.JSON.valueToTree(o);
    }

    @Test
    void actionIdsMatchPythonUuid5() {
        assertEquals("3080346f-1266-5ce9-8191-275f66f73412", LegalActions.actionId("entity-1", "play"));
        assertEquals("c7f39112-2181-5d3a-9f4a-b188ab405e1e",
                LegalActions.actionId("9601e7c9-3db7-498e-8f69-b776066fcfa4", "energy"));
    }

    @Test
    void idleTimerPayloadMatchesPython() {
        assertEquals(json(Msgs.obj("playerID", "p", "inactivityDuration", 15000, "endTurnDuration", 15000, "forced", false)),
                json(Msgs.idleTimer("p", 30000)));
        assertEquals(json(Msgs.obj("playerID", "p", "inactivityDuration", 5000, "endTurnDuration", 15000, "forced", false)),
                json(Msgs.idleTimer("p", 20000)));
        assertEquals(0, json(Msgs.idleTimer("p", -5)).get("endTurnDuration").asInt());
    }

    @Test
    void bracketIsCompleteAndNestedSequencesRideTheirOwnId() {
        AtomicInteger n = new AtomicInteger();
        List<Map<String, Object>> out = Msgs.bracket("g", "Draw",
                List.of(Msgs.activePlayerSet("g", "acct"),
                        new Msgs.NestedSequence(GameSequence.GROUPED_MOVE, List.of(Msgs.shuffled("g", "deck")))),
                () -> "id" + n.incrementAndGet());
        List<String> names = out.stream().map(m -> json(m).get("msg").get("name").asText()).toList();
        assertEquals(List.of("StartSequence", "ActivePlayerSet", "StartSequence", "Shuffled", "StopSequence", "StopSequence"), names);
        List<String> ids = out.stream().map(m -> json(m).get("sequenceID").asText()).toList();
        assertEquals(List.of("id1", "id1", "id2", "id2", "id2", "id1"), ids);
        JsonNode start = json(out.get(0)).get("msg").get("value");
        assertEquals("Draw", start.get("name").asText());
        assertEquals("id1", start.get("sequenceID").asText());
        assertEquals("g", start.get("gameID").asText());
        assertEquals("GroupedMove", json(out.get(2)).get("msg").get("value").get("name").asText());
    }

    @Test
    void standaloneMessagesUseTheEmptySequenceId() {
        assertEquals("00000000-0000-0000-0000-000000000000",
                json(Msgs.standalone("g", Msgs.shuffled("g", "d"))).get("sequenceID").asText());
    }

    @Test
    void selectionKindVocabularyIsComplete() {
        assertEquals(29, com.ptcgo.revival.game.wire.SelectionKind.values().length);
        assertEquals("KnockoutPokemonTargetInformation", com.ptcgo.revival.game.wire.SelectionKind.KNOCKOUT_POKEMON.wire());
        assertEquals("Ability", com.ptcgo.revival.game.wire.GameConstants.TARGET_TYPE_MAIN_TURN);
    }

    @Test
    void sameSeedSameBoard() {
        Board a = board(5), b = board(5), c = board(6);
        a.shuffleDeck(GameFixtures.A);
        b.shuffleDeck(GameFixtures.A);
        c.shuffleDeck(GameFixtures.A);
        List<String> ga = a.cardsIn(GameFixtures.A, "deck").stream().map(x -> x.card.guid()).toList();
        List<String> gb = b.cardsIn(GameFixtures.A, "deck").stream().map(x -> x.card.guid()).toList();
        List<String> gc = c.cardsIn(GameFixtures.A, "deck").stream().map(x -> x.card.guid()).toList();
        assertEquals(ga, gb);
        assertNotEquals(ga, gc);
        assertEquals(a.cardsIn(GameFixtures.A, "deck").get(0).entityId, b.cardsIn(GameFixtures.A, "deck").get(0).entityId);
        // entity ids are valid GUIDs
        java.util.UUID.fromString(a.newId());
    }

    @Test
    void serializationHidesWhatTheViewerMayNotSee() {
        Board b = board(1);
        b.drawCards(GameFixtures.A, 7);
        JsonNode forA = json(b.serialize(GameFixtures.A)), forB = json(b.serialize(GameFixtures.B));
        assertEquals(2, forA.get("playerAccounts").size());
        // owner sees hand cards, opponent sees them un-introduced (attributes null); the deck is hidden from everyone
        long visibleToA = 0, visibleToB = 0;
        for (CardEntity c : b.cardsIn(GameFixtures.A, "hand")) {
            visibleToA += findNode(forA, c.entityId).get("attributes").isNull() ? 0 : 1;
            visibleToB += findNode(forB, c.entityId).get("attributes").isNull() ? 0 : 1;
        }
        assertEquals(7, visibleToA);
        assertEquals(0, visibleToB);
        CardEntity top = b.cardsIn(GameFixtures.A, "deck").get(0);
        assertTrue(findNode(forA, top.entityId).get("attributes").isNull());
        // bench carries its slot count
        PlayArea bench = b.area(GameFixtures.A, "bench");
        assertEquals(5, bench.getInt(Attr.AREA_SLOTS, 0));
    }

    private static JsonNode findNode(JsonNode n, String id) {
        if (id.equals(n.path("entityID").asText())) return n;
        for (JsonNode c : n.path("children")) {
            JsonNode f = findNode(c, id);
            if (f != null) return f;
        }
        for (String k : List.of("entities")) if (n.has(k)) return findNode(n.get(k), id);
        return null;
    }

    @Test
    void damageCountersAreHpMinusMaxAndSerializeAsOriginalValue() {
        Board b = board(2);
        CardEntity zap = b.cardsIn(GameFixtures.A, "deck").stream().filter(c -> c.card.guid().equals(TestCatalog.ZAP)).findFirst().get();
        assertEquals(60, zap.maxHp());
        zap.set(Attr.HP, 40);
        assertEquals(20, zap.damage());
        Map<String, Object> hp = zap.serializeAttributes().stream().filter(m -> m.get("name").equals(Attr.HP)).findFirst().get();
        assertEquals(40, hp.get("value"));
        assertEquals(60, hp.get("originalValue"));
        zap.setConditions(List.of(Attr.ASLEEP, Attr.POISONED));
        JsonNode msg = json(Msgs.conditionAttribute(GameFixtures.GAME, zap));
        assertEquals(List.of("Asleep", "Poisoned"), List.of(msg.get("value").get("attribute").get("value").get(0).asText(),
                msg.get("value").get("attribute").get("value").get(1).asText()));
    }

    @Test
    void attachedEnergyAndCostChecks() {
        Board b = board(3);
        CardEntity zap = b.cardsIn(GameFixtures.A, "deck").stream().filter(c -> c.card.guid().equals(TestCatalog.ZAP)).findFirst().get();
        List<CardEntity> energies = b.cardsIn(GameFixtures.A, "deck").stream().filter(CardEntity::isEnergy).limit(2).toList();
        for (CardEntity e : energies) b.attachCard(e.entityId, zap.entityId);
        assertEquals(2, Board.attachedEnergies(zap).size());
        assertTrue(LegalActions.attackCostSatisfied(Map.of("Lightning", 1, "Colorless", 1), Board.attachedEnergies(zap)));
        assertFalse(LegalActions.attackCostSatisfied(Map.of("Fire", 1), Board.attachedEnergies(zap)));
        assertFalse(LegalActions.attackCostSatisfied(Map.of("Lightning", 3), Board.attachedEnergies(zap)));
    }

    @Test
    void weaknessDoublesAndResistanceSubtracts() {
        Board b = board(4);
        List<CardEntity> deck = b.cardsIn(GameFixtures.B, "deck");
        CardEntity zapper = deck.stream().filter(c -> c.card.guid().equals(TestCatalog.ZAP)).findFirst().get();
        CardEntity defender = deck.stream().filter(c -> c.card.guid().equals(TestCatalog.ZAPPER)).findFirst().get();
        // Zapper is weak to type 6 (Fire); a Lightning attacker is not
        DamageCalc plain = DamageCalc.compute(b, zapper, defender, 30, true, true, "t", List.of());
        assertEquals(30, plain.amount);
        assertFalse(plain.weaknessHit);
        CardEntity fire = b.cardsIn(GameFixtures.A, "deck").stream().filter(c -> c.card.guid().equals(TestCatalog.ZAP)).findFirst().get();
        fire.set(Attr.POKEMON_TYPES, WargCodec.JSON.valueToTree(List.of(6)));
        DamageCalc weak = DamageCalc.compute(b, fire, defender, 30, true, true, "t", List.of());
        assertEquals(60, weak.amount);
        assertTrue(weak.weaknessHit);
        // bench damage is not modified
        assertEquals(30, DamageCalc.compute(b, fire, defender, 30, true, false, "t", List.of()).amount);
        // resistance
        defender.set(Attr.RESISTANCE_TYPES, WargCodec.JSON.valueToTree(List.of(6)));
        defender.set(Attr.WEAKNESS_TYPES, WargCodec.JSON.valueToTree(List.of()));
        DamageCalc res = DamageCalc.compute(b, fire, defender, 50, true, true, "t", List.of());
        assertEquals(20, res.amount);
        assertTrue(res.resistanceHit);
    }

    @Test
    void entityMovedStampsTheClientSlot() {
        Board b = board(1);
        PlayArea bench = b.area(GameFixtures.A, "bench");
        CardEntity c = b.cardsIn(GameFixtures.A, "deck").get(0);
        JsonNode m = json(Msgs.entityMoved(b, c.entityId, bench.entityId, 3));
        assertEquals("EntityMoved", m.get("name").asText());
        assertEquals(3, m.get("value").get("positionInParent").asInt());
        assertEquals(300, m.get("value").get("animDuration").asInt());
        assertEquals(3, c.boardSlot);
        List<String> keys = new ArrayList<>();
        m.get("value").fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("gameID", "entityID", "destinationID", "positionInParent", "animDuration"), keys);
    }
}
