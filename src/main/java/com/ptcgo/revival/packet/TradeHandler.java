package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.db.TradeRepository;
import com.ptcgo.revival.db.TradeRepository.Filter;
import com.ptcgo.revival.db.TradeRepository.Offer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Trade Room "lots" protocol. Port of ptcgo/packets/handlers/trade.py (the Python server has no separate
 * friend-to-friend trade: a private lot with {@code privateTo} is the offer between two players, a lot without it is
 * public). Accepting swaps the cards atomically ({@link TradeRepository#accept}).
 *
 * <p>Like Python, offers never expire, a private recipient need not be a friend, and only tradable copies stored in
 * the {@code collection} table can be offered (the virtual basic-energy copies cannot).
 */
public final class TradeHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(TradeHandler.class);
    static final String EMPTY_GUID = "00000000-0000-0000-0000-000000000000";
    static final long LOT_LIFETIME_MS = 30L * 24 * 3600 * 1000;
    static final long TRAINER_TOKENS = -1784319558L;

    private final TradeRepository trades;

    public TradeHandler(TradeRepository trades) {
        this.trades = trades;
    }

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("ViewMyLotsCount", (c, v, rid, f) -> c.send("MyLotsRetrievedCount", Map.of("count",
                c.account() == null ? 0 : trades.count(Filter.open().withSender(id(c)))), rid));
        m.put("ViewAllPrivateTradesCount", (c, v, rid, f) -> c.send("PrivateLotsRetrievedCount", Map.of("count",
                c.account() == null ? 0 : trades.count(Filter.open().withRecipient(id(c)))), rid));
        m.put("ViewAllPublicTradesCount", (c, v, rid, f) -> c.send("LotsRetrievedCount", Map.of("count",
                trades.count(Filter.open().withPublic().withExcludeSender(id(c)))), rid));
        m.put("ViewTradeHistory2Count", (c, v, rid, f) -> c.send("TradeHistoryRetrievedCount", Map.of("count",
                c.account() == null ? 0 : trades.history(id(c), 500).size()), rid));
        m.put("ViewAllPublicTradesWithPagination", this::viewPublic);
        m.put("ViewAllPrivateTradesWithPagination", this::viewPrivate);
        m.put("ViewMyLotsWithPagination", this::viewMine);
        m.put("SearchForLots", this::search);
        m.put("CreateLotWithArchetypes", this::create);
        m.put("RemoveLot", this::remove);
        m.put("AcceptTrade", this::accept);
        m.put("ViewTradeHistory2", this::history);
        m.put("SetArchetypeForTradeCount", this::setForTrade);
        m.put("SetArchetypeReview", this::setReview);
        m.put("GetCreateLotPrices", this::prices);
        return m;
    }

    private static String id(ClientContext c) {
        return c.account() == null ? null : c.account().accountId();
    }

    private static int intOf(JsonNode v, String f, int dflt) {
        return v != null && v.hasNonNull(f) ? v.get(f).asInt(dflt) : dflt;
    }

    // ---- shapes ----------------------------------------------------------------------------------

    /** Item (B.m) shape; one per copy. */
    static Map<String, Object> item(String owner, String guid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemID", UUID.randomUUID().toString());
        m.put("ownerID", owner);
        m.put("archetypeID", guid);
        m.put("lockID", null);
        m.put("created", System.currentTimeMillis() / 1000);
        m.put("isTradable", true);
        m.put("name", "");
        m.put("invoiceID", null);
        m.put("attributes", List.of());
        return m;
    }

    static Map<String, Object> lot(Offer o) {
        List<Map<String, Object>> items = new ArrayList<>();
        o.offering().forEach((guid, n) -> {
            for (int i = 0; i < n; i++) items.add(item(o.senderId(), guid));
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.offerId());
        m.put("items", items);
        m.put("price", 0.0);
        m.put("owner", o.senderId());
        m.put("ownerName", o.senderName() == null ? "" : o.senderName());
        m.put("lockID", EMPTY_GUID);
        m.put("lockedBy", EMPTY_GUID);
        m.put("lockedByName", "");
        m.put("expirationDate", System.currentTimeMillis() + LOT_LIFETIME_MS);
        m.put("expirationHR", "30 days");
        m.put("expirationType", 0);
        m.put("isDeck", false);
        m.put("cardPrice", o.requesting());
        m.put("message", "");
        return m;
    }

    private static List<Map<String, Object>> lots(List<Offer> offers) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Offer o : offers) l.add(lot(o));
        return l;
    }

    private static void error(ClientContext c, String messageName, String reason, int rid) {
        c.send(messageName, Map.of("error", Map.of("id", reason)), rid);
    }

    /** Pushes a collection refresh to any online client (self or trade partner). */
    private static void syncCollection(ClientContext c) throws SQLException {
        if (c.account() == null) return;
        c.send("CollectionCountFound", Map.of("collectionCountList", DataSyncHandler.collectionPayload(c)), 0);
    }

    // ---- views -----------------------------------------------------------------------------------

    private void viewPublic(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        List<Offer> o = trades.list(Filter.open().withPublic().withExcludeSender(id(c)), intOf(v, "offset", 0), intOf(v, "limit", 50));
        c.send("LotsRetrieved", Map.of("lots", lots(o)), rid);
    }

    private void viewPrivate(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        List<Offer> o = trades.list(Filter.open().withRecipient(id(c)), intOf(v, "offset", 0), intOf(v, "limit", 50));
        c.send("PrivateLotsRetrieved", Map.of("lots", lots(o), "counterOffers", List.of()), rid);
    }

    private void viewMine(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        List<Offer> o = trades.list(Filter.open().withSender(id(c)), intOf(v, "offset", 0), intOf(v, "limit", 50));
        c.send("MyLotsRetrieved", Map.of("lots", lots(o), "offers", List.of()), rid);
    }

    private void search(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        if (v != null && v.get("archetypes") != null && v.get("archetypes").isArray()) {
            v.get("archetypes").forEach(n -> wanted.add(n.asText().toLowerCase(Locale.ROOT)));
        }
        List<Offer> matches = new ArrayList<>();
        for (Offer o : trades.list(Filter.open().withPublic(), 0, 500)) {
            if (o.senderId().equals(id(c))) continue;
            boolean hit = wanted.isEmpty() || o.offering().keySet().stream().anyMatch(g -> wanted.contains(g.toLowerCase(Locale.ROOT)));
            if (hit) matches.add(o);
        }
        c.send("LotsRetrieved", Map.of("lots", lots(matches)), rid);
    }

    private void history(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        int offset = Math.max(0, intOf(v, "offset", 0));
        int limit = Math.max(0, intOf(v, "limit", 50));
        List<Offer> all = trades.history(id(c), 500);
        List<Offer> page = all.subList(Math.min(offset, all.size()), Math.min(offset + limit, all.size()));
        c.send("TradeHistoryRetrieved", Map.of("offset", offset, "history", lots(page)), rid);
    }

    // ---- mutations -------------------------------------------------------------------------------

    /** Accepts {guid: count} or [guid, ...] and returns {guid: count}. */
    static Map<String, Integer> normalizeCounts(JsonNode value) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (value == null) return out;
        if (value.isObject()) {
            value.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asInt(0)));
        } else if (value.isArray()) {
            value.forEach(n -> out.merge(n.asText(), 1, Integer::sum));
        }
        return out;
    }

    private void create(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        Map<String, Integer> offering = normalizeCounts(v == null ? null : v.get("forTrade"));
        Map<String, Integer> requesting = normalizeCounts(v == null ? null : v.get("toReceive"));
        String privateTo = v != null && v.hasNonNull("privateTo") ? v.get("privateTo").asText() : null;
        if (privateTo != null && (privateTo.isEmpty() || privateTo.equals(EMPTY_GUID))) privateTo = null;
        LOG.info("[TCP] [{}] CreateLot: offering={} requesting={} privateTo={}", c.remoteAddress(), offering, requesting, privateTo);

        TradeRepository.Result r = trades.create(id(c), offering, requesting, privateTo);
        if (r.error() != null) {
            error(c, "ErrorCreatingLot", r.error(), rid);
            return;
        }
        c.send("LotCreated", Map.of("lotID", r.offer().offerId()), rid);
        if (privateTo != null) {
            c.services().registry().find(privateTo).ifPresent(t -> t.send("LotCreatedForYou",
                    Map.of("byUserName", c.account().displayName()), 0));
        }
    }

    private void remove(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        String lotId = v != null && v.hasNonNull("lotID") ? v.get("lotID").asText() : "";
        if (c.account() == null || !trades.cancel(lotId, id(c))) {
            error(c, "ErrorRemovingLot", "That trade offer could not be removed. Please try again.", rid);
            return;
        }
        c.send("LotRemoved", Map.of("lotID", lotId, "reason", "Removed"), rid);
    }

    private void accept(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        String lotId = v != null && v.hasNonNull("lotID") ? v.get("lotID").asText() : "";
        TradeRepository.Result r = trades.accept(lotId, id(c));
        if (r.error() != null) {
            error(c, "ErrorPurchasingLot", r.error(), rid);
            return;
        }
        LOG.info("[TCP] Trade {} accepted by {} (from {}).", lotId, id(c), r.offer().senderId());
        c.send("LotSold", Map.of("lotID", lotId), rid);
        syncCollection(c);
        ClientContext owner = c.services().registry().find(r.offer().senderId()).orElse(null);
        if (owner != null) {
            owner.send("LotSold", Map.of("lotID", lotId), 0);
            syncCollection(owner);
        }
    }

    // ---- archetype flags / prices ----------------------------------------------------------------

    private void setForTrade(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        String archetype = v != null && v.hasNonNull("archetypeID") ? v.get("archetypeID").asText() : "";
        if (archetype.isEmpty()) return;
        int forTrade = intOf(v, "forTrade", 0);
        players(c).setFlag(id(c), archetype, null, forTrade, null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("archetypeID", v.get("archetypeID").asText());
        m.put("forTrade", forTrade);
        c.send("ArchetypeForTradeSet", m, rid);
    }

    private void setReview(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        String archetype = v != null && v.hasNonNull("archetypeID") ? v.get("archetypeID").asText() : "";
        if (archetype.isEmpty()) return;
        int review = v.hasNonNull("review") ? v.get("review").asInt(0) : intOf(v, "forTrade", 0);
        players(c).setFlag(id(c), archetype, null, null, review);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("archetypeID", archetype);
        m.put("review", review);
        c.send("ArchetypeReviewSet", m, rid);
    }

    private static PlayerRepository players(ClientContext c) {
        return c.services().players();
    }

    private void prices(ClientContext c, JsonNode v, int rid, int f) {
        Map<String, Object> prices = new LinkedHashMap<>();
        int[] hours = {8, 24, 48};
        for (int i = 0; i < hours.length; i++) prices.put(String.valueOf(i), Map.of("name", TRAINER_TOKENS, "value", hours[i]));
        c.send("CreateLotPrices", Map.of("prices", prices), rid);
    }
}
