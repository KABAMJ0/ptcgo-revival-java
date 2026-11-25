package com.ptcgo.revival.game.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.game.wire.Attr;
import com.ptcgo.revival.game.wire.GameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The full tree layout of the Playmat with an O(1) entity cache (ptcgo/game/models/board.py BoardState).
 * Randomness (entity ids, deck shuffles) comes from the injected {@link RandomGenerator}.
 */
public final class Board {
    private static final Logger LOG = LoggerFactory.getLogger(Board.class);
    private static final List<String> GLOBAL_PILES = List.of("outOfPlay", "activeStadium", "activeTrainer");
    private static final List<String> PLAYER_PILES =
            List.of("deck", "hand", "bench", "activePokemonArea", "discard", "prizePile", "lostZone");

    public final String gameId;
    public final List<String> playerIds;
    public final PlayMat playmat;
    private final Map<String, Entity> cache = new HashMap<>();
    private final RandomGenerator rng;
    public Map<String, Object> gameOptions = new LinkedHashMap<>(Map.of("theme", "ForestPlaymat"));
    /** player id -> prize cards dealt at setup; prizes taken = dealt - remaining. */
    public final Map<String, Integer> prizesDealt = new HashMap<>();
    public String firstPlayerId;

    public Board(String gameId, List<String> playerIds, RandomGenerator rng) {
        this.gameId = gameId;
        this.playerIds = List.copyOf(playerIds);
        this.rng = rng;
        this.playmat = new PlayMat(newId());
        register(playmat);
        for (String pile : GLOBAL_PILES) {
            PlayArea area = new PlayArea(newId(), pile, null);
            playmat.addChild(area);
            register(area);
        }
        for (String pid : playerIds) {
            PlayerEntity pe = new PlayerEntity(newId(), pid);
            playmat.addChild(pe);
            register(pe);
            for (String pile : PLAYER_PILES) {
                PlayArea area = new PlayArea(newId(), pile, pid);
                // BenchLayout spaces cards by dividing by this slot count; without it every card gets a NaN transform.
                if (pile.equals("bench")) area.set(Attr.AREA_SLOTS, GameConstants.BENCH_SLOT_COUNT);
                pe.addChild(area);
                register(area);
            }
        }
    }

    /** A GUID-shaped random id drawn from the injected generator (deterministic when the generator is seeded). */
    public String newId() {
        long hi = (rng.nextLong() & 0xffffffffffff0fffL) | 0x4000L;
        long lo = (rng.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(hi, lo).toString();
    }

    private void register(Entity e) {
        cache.put(e.entityId, e);
        for (Entity c : e.children) register(c);
    }

    private void unregister(Entity e) {
        cache.remove(e.entityId);
        for (Entity c : e.children) unregister(c);
    }

    public Entity entity(String id) {
        return id == null ? null : cache.get(id);
    }

    public CardEntity card(String id) {
        return entity(id) instanceof CardEntity c ? c : null;
    }

    public PlayerEntity playerEntity(String playerId) {
        for (Entity c : playmat.children) {
            if (c instanceof PlayerEntity p && playerId.equals(p.owningPlayerId)) return p;
        }
        return null;
    }

    public PlayArea area(String playerId, String name) {
        PlayerEntity p = playerEntity(playerId);
        if (p == null) return null;
        for (Entity c : p.children) if (c instanceof PlayArea a && name.equals(a.areaName())) return a;
        return null;
    }

    public PlayArea globalArea(String name) {
        for (Entity c : playmat.children) if (c instanceof PlayArea a && name.equals(a.areaName())) return a;
        return null;
    }

    public String opponentOf(String playerId) {
        for (String p : playerIds) if (!p.equals(playerId)) return p;
        throw new IllegalArgumentException("no opponent for " + playerId);
    }

    public List<CardEntity> cardsIn(String playerId, String areaName) {
        PlayArea a = area(playerId, areaName);
        List<CardEntity> out = new ArrayList<>();
        if (a != null) for (Entity c : a.children) if (c instanceof CardEntity ce) out.add(ce);
        return out;
    }

    public void addCardToArea(CardEntity card, PlayArea area) {
        area.addChild(card);
        card.owningPlayerId = area.owningPlayerId;
        register(card);
    }

    /** Moves a card entity to a play area. Position null appends. */
    public boolean moveCard(String cardId, String toAreaId, Integer position) {
        CardEntity card = card(cardId);
        Entity to = entity(toAreaId);
        if (card == null || !(to instanceof PlayArea area)) return false;
        if (card.parent != null) card.parent.removeChild(card);
        area.addChild(card, position);
        card.owningPlayerId = area.owningPlayerId;
        if (card.faceUp && !"prizePile".equals(area.areaName())) card.faceUp = false;
        return true;
    }

    public boolean moveCard(String cardId, String toAreaId) {
        return moveCard(cardId, toAreaId, null);
    }

    /** Reparents a card underneath another CARD entity (energy attachments and evolution stacks). */
    public boolean attachCard(String cardId, String toEntityId) {
        CardEntity card = card(cardId);
        CardEntity target = card(toEntityId);
        if (card == null || target == null || card == target) return false;
        if (card.parent != null) card.parent.removeChild(card);
        target.addChild(card);
        card.owningPlayerId = target.owningPlayerId;
        return true;
    }

    /** Randomizes the order of the player's deck in place. */
    public boolean shuffleDeck(String playerId) {
        PlayArea deck = area(playerId, "deck");
        if (deck == null) return false;
        Collections.shuffle(deck.children, new java.util.Random(rng.nextLong()));
        return true;
    }

    /** One card moved by deal/draw, in deal order, for EntityMoved messages. */
    public record Moved(String entityId, String destinationId, int position, CardEntity card) {}

    /** Moves the top {@code count} cards (end of the children list) of the deck into one of the player's areas. */
    public List<Moved> dealFromDeck(String playerId, String areaName, int count) {
        PlayArea deck = area(playerId, "deck");
        PlayArea dest = area(playerId, areaName);
        List<Moved> moved = new ArrayList<>();
        if (deck == null || dest == null) return moved;
        for (int i = 0; i < count; i++) {
            if (deck.children.isEmpty()) break;
            CardEntity card = (CardEntity) deck.children.get(deck.children.size() - 1);
            int position = dest.children.size();
            if (!moveCard(card.entityId, dest.entityId)) break;
            moved.add(new Moved(card.entityId, dest.entityId, position, card));
        }
        if (areaName.equals("prizePile") && !moved.isEmpty()) prizesDealt.merge(playerId, moved.size(), Integer::sum);
        return moved;
    }

    public List<Moved> drawCards(String playerId, int count) {
        return dealFromDeck(playerId, "hand", count);
    }

    public int prizesTaken(String playerId) {
        PlayArea a = area(playerId, "prizePile");
        if (a == null) return 0;
        return Math.max(0, prizesDealt.getOrDefault(playerId, 0) - a.children.size());
    }

    // ---- setup queries -------------------------------------------------------------------------

    public static boolean isBasicPokemon(CardEntity c) {
        return c.isPokemon() && c.card.intAttribute(Attr.CARD_TYPE, -1) == Attr.CT_POKEMON
                && c.card.intAttribute(Attr.STAGE, Attr.STAGE_BASIC) == Attr.STAGE_BASIC;
    }

    public List<CardEntity> basicPokemonInHand(String playerId) {
        List<CardEntity> out = new ArrayList<>();
        for (CardEntity c : cardsIn(playerId, "hand")) if (isBasicPokemon(c)) out.add(c);
        return out;
    }

    /** Cards playable as the opening Active (Basics; the effect seam may widen this later). */
    public List<CardEntity> setupActiveCandidates(String playerId) {
        return basicPokemonInHand(playerId);
    }

    public List<CardEntity> setupBenchCandidates(String playerId) {
        return basicPokemonInHand(playerId);
    }

    /** True if the player has a Basic Pokemon anywhere in deck or hand (guards the mulligan loop). */
    public boolean playerHasAnyBasic(String playerId) {
        for (String a : List.of("deck", "hand")) {
            for (CardEntity c : cardsIn(playerId, a)) if (isBasicPokemon(c)) return true;
        }
        return false;
    }

    /** The player's top-level Pokemon: the Active plus every benched one. */
    public List<CardEntity> pokemonInPlay(String playerId) {
        List<CardEntity> out = new ArrayList<>();
        for (String a : List.of("activePokemonArea", "bench")) {
            for (CardEntity c : cardsIn(playerId, a)) if (c.isPokemon()) out.add(c);
        }
        return out;
    }

    public CardEntity activePokemon(String playerId) {
        for (CardEntity c : cardsIn(playerId, "activePokemonArea")) if (c.isPokemon()) return c;
        return null;
    }

    public List<CardEntity> benchPokemon(String playerId) {
        List<CardEntity> out = new ArrayList<>();
        for (CardEntity c : cardsIn(playerId, "bench")) if (c.isPokemon()) out.add(c);
        return out;
    }

    /** Lowest bench slot not occupied by a current bench child's stamp (the client never renumbers slots). */
    public int freeBenchSlot(String playerId) {
        PlayArea bench = area(playerId, "bench");
        if (bench == null) return 0;
        Set<Integer> occupied = new HashSet<>();
        for (Entity c : bench.children) occupied.add(benchSlotOf(c));
        for (int s = 0; s < GameConstants.BENCH_SLOT_COUNT; s++) if (!occupied.contains(s)) return s;
        return bench.children.size();
    }

    public static int benchSlotOf(Entity e) {
        if (e.boardSlot != null) return e.boardSlot;
        if (e.parent != null) {
            int i = e.parent.children.indexOf(e);
            if (i >= 0) return i;
        }
        return 0;
    }

    /** Every Energy card attached anywhere under a Pokemon's stack. */
    public static List<CardEntity> attachedEnergies(CardEntity pokemon) {
        List<CardEntity> out = new ArrayList<>();
        List<Entity> stack = new ArrayList<>(pokemon.children);
        while (!stack.isEmpty()) {
            Entity e = stack.remove(stack.size() - 1);
            if (e instanceof CardEntity c && c.isEnergy()) out.add(c);
            stack.addAll(e.children);
        }
        return out;
    }

    /** Every entity attached under a Pokemon, depth-first (the stack that falls to the discard on a knockout). */
    public static List<CardEntity> stackDescendants(CardEntity pokemon) {
        List<CardEntity> out = new ArrayList<>();
        for (Entity c : pokemon.children) {
            if (c instanceof CardEntity ce) {
                out.add(ce);
                out.addAll(stackDescendants(ce));
            }
        }
        return out;
    }

    public void returnHandToDeck(String playerId) {
        PlayArea hand = area(playerId, "hand");
        PlayArea deck = area(playerId, "deck");
        if (hand == null || deck == null) return;
        for (Entity c : new ArrayList<>(hand.children)) moveCard(c.entityId, deck.entityId);
    }

    /**
     * Decodes the deck definition ({"piles":{"deck":[guid,...]}} or "CakePile", or {"cards":[{guid,count}]}) into
     * card entities in the deck area, and sets the playmat GX/VSTAR token flags on the PlayerEntity.
     */
    public void populateDeck(String playerId, JsonNode deckData, Catalog catalog) {
        PlayArea deck = area(playerId, "deck");
        if (deck == null || deckData == null) return;
        JsonNode actual = deckData.has("deck") && deckData.get("deck").isObject() ? deckData.get("deck") : deckData;
        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonNode cards = actual.path("cards");
        if (cards.isArray() && cards.size() > 0) {
            for (JsonNode ci : cards) {
                String g = ci.path("guid").asText("");
                if (!g.isEmpty()) counts.merge(g.toLowerCase(), ci.path("count").asInt(1), Integer::sum);
            }
        } else {
            JsonNode piles = actual.path("piles");
            JsonNode list = piles.has("deck") ? piles.get("deck") : piles.path("CakePile");
            if (list.isArray()) for (JsonNode g : list) if (!g.asText("").isEmpty()) counts.merge(g.asText().toLowerCase(), 1, Integer::sum);
        }
        boolean gx = false, vstar = false;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Catalog.Card card = catalog.card(e.getKey());
            if (card == null) {
                LOG.warn("[Board] No card for GUID {}", e.getKey());
                continue;
            }
            String dn = card.displayName() == null ? "" : card.displayName();
            int stage = card.intAttribute(Attr.STAGE, -1);
            int rarity = card.intAttribute(200550, -1);
            if (stage == Attr.STAGE_VSTAR || rarity == 8 || dn.contains("VSTAR")
                    || card.searchableBy().stream().anyMatch(s -> s.toLowerCase().contains("vstar"))) vstar = true;
            if (rarity == 5 || dn.contains("GX")
                    || card.searchableBy().stream().anyMatch(s -> s.toLowerCase().contains("gx"))) gx = true;
            for (int i = 0; i < e.getValue(); i++) {
                CardEntity ce = new CardEntity(newId(), card, playerId);
                addCardToArea(ce, deck);
            }
        }
        PlayerEntity pe = playerEntity(playerId);
        if (pe != null) {
            pe.set(Attr.HAS_GX_TOKEN, gx);
            pe.set(Attr.HAS_VSTAR_TOKEN, vstar);
        }
    }

    /** Syncs each bench's slot order and AREA_EMPTY_SLOTS for SGS loads. */
    private void refreshBenchGaps() {
        for (String pid : playerIds) {
            PlayArea bench = area(pid, "bench");
            if (bench == null) continue;
            bench.children.sort(java.util.Comparator.comparingInt(Board::benchSlotOf));
            Set<Integer> occupied = new HashSet<>();
            for (Entity c : bench.children) occupied.add(benchSlotOf(c));
            int slots = bench.getInt(Attr.AREA_SLOTS, GameConstants.BENCH_SLOT_COUNT);
            List<Integer> empty = new ArrayList<>();
            for (int s = 0; s < slots; s++) if (!occupied.contains(s)) empty.add(s);
            bench.set(Attr.AREA_EMPTY_SLOTS, empty);
        }
    }

    /** SerializedGameState value for one viewer: cards not visible to the viewer serialize un-introduced. */
    public Map<String, Object> serialize(String viewerId) {
        refreshBenchGaps();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameID", gameId);
        m.put("playerAccounts", playerIds);
        m.put("gameOptions", gameOptions);
        m.put("entities", playmat.serialize(viewerId));
        return m;
    }
}
