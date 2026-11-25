package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.db.TradeRepository;
import com.ptcgo.revival.packet.SocialTestSupport.Client;
import com.ptcgo.revival.social.FriendMatchStarter;
import com.ptcgo.revival.social.GameChatRouter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TradeIntegrationTest {
    static final String PIKA = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String ONIX = "bbbbbbbb-0000-0000-0000-000000000002";

    private static SocialTestSupport env() throws Exception {
        return new SocialTestSupport(FriendMatchStarter.UNAVAILABLE, GameChatRouter.NONE);
    }

    private static Map<String, Integer> tradable(PlayerRepository p, String account) throws Exception {
        return p.collection(account).stream().collect(Collectors.toMap(
                PlayerRepository.CollectionRow::archetypeId, PlayerRepository.CollectionRow::tradable));
    }

    @Test
    void privateTradeSwapsCardsAtomicallyAndNotifiesBoth() throws Exception {
        try (SocialTestSupport env = env(); Client ash = env.login("Ash"); Client misty = env.login("Misty")) {
            PlayerRepository players = env.services.players();
            players.addToCollection(ash.accountId, Map.of(PIKA, 3), true);
            players.addToCollection(misty.accountId, Map.of(ONIX, 2), true);

            ash.send("CreateLotWithArchetypes", Map.of("forTrade", Map.of(PIKA, 2), "toReceive", List.of(ONIX),
                    "privateTo", misty.accountId), 1);
            String lotId = ash.expect("LotCreated").get("lotID").asText();
            assertEquals(1, ash.lastRequestId);
            assertEquals("Ash", misty.expect("LotCreatedForYou").get("byUserName").asText());

            misty.send("ViewAllPrivateTradesCount", Map.of(), 2);
            assertEquals(1, misty.expect("PrivateLotsRetrievedCount").get("count").asInt());
            ash.send("ViewAllPrivateTradesCount", Map.of(), 3);
            assertEquals(0, ash.expect("PrivateLotsRetrievedCount").get("count").asInt());
            ash.send("ViewMyLotsCount", Map.of(), 4);
            assertEquals(1, ash.expect("MyLotsRetrievedCount").get("count").asInt());

            misty.send("ViewAllPrivateTradesWithPagination", Map.of("offset", 0, "limit", 50), 5);
            JsonNode priv = misty.expect("PrivateLotsRetrieved");
            JsonNode lot = priv.get("lots").get(0);
            assertEquals(lotId, lot.get("id").asText());
            assertEquals(2, lot.get("items").size());
            assertEquals(PIKA, lot.get("items").get(0).get("archetypeID").asText());
            assertEquals(ash.accountId, lot.get("items").get(0).get("ownerID").asText());
            assertTrue(lot.get("items").get(0).get("isTradable").asBoolean());
            assertEquals("Ash", lot.get("ownerName").asText());
            assertEquals(1, lot.get("cardPrice").get(ONIX).asInt());
            assertEquals(0, priv.get("counterOffers").size());
            assertEquals(TradeHandler.EMPTY_GUID, lot.get("lockID").asText());

            // A third party cannot take a private lot.
            try (Client brock = env.login("Brock")) {
                brock.send("AcceptTrade", Map.of("lotID", lotId), 6);
                assertEquals("trade.errorPurchasingLotDialog.bodyFormat",
                        brock.expect("ErrorPurchasingLot").get("error").get("id").asText());
            }

            misty.send("AcceptTrade", Map.of("lotID", lotId), 7);
            misty.expect("LotSold");
            assertEquals(7, misty.lastRequestId);
            JsonNode mc = misty.expect("CollectionCountFound");
            assertTrue(mc.get("collectionCountList").toString().contains(PIKA));
            ash.expect("LotSold");
            assertEquals(0, ash.lastRequestId);
            ash.expect("CollectionCountFound");

            assertEquals(Map.of(PIKA, 1, ONIX, 1), tradable(players, ash.accountId));
            assertEquals(Map.of(PIKA, 2, ONIX, 1), tradable(players, misty.accountId));

            // Already sold.
            misty.send("AcceptTrade", Map.of("lotID", lotId), 8);
            misty.expect("ErrorPurchasingLot");
            // History for both parties.
            ash.send("ViewTradeHistory2Count", Map.of(), 9);
            assertEquals(1, ash.expect("TradeHistoryRetrievedCount").get("count").asInt());
            misty.send("ViewTradeHistory2", Map.of("offset", 0, "limit", 10), 10);
            JsonNode h = misty.expect("TradeHistoryRetrieved");
            assertEquals(0, h.get("offset").asInt());
            assertEquals(lotId, h.get("history").get(0).get("id").asText());
        }
    }

    @Test
    void validationErrorsLeaveCollectionsUntouched() throws Exception {
        try (SocialTestSupport env = env(); Client ash = env.login("Ash"); Client misty = env.login("Misty")) {
            PlayerRepository players = env.services.players();
            players.addToCollection(ash.accountId, Map.of(PIKA, 1), true);
            players.addToCollection(ash.accountId, Map.of(ONIX, 5), false); // non-tradable copies do not count

            ash.send("CreateLotWithArchetypes", Map.of("forTrade", Map.of(PIKA, 2), "toReceive", Map.of(ONIX, 1)), 1);
            assertEquals("trade.createLot.notEnoughCards.body", ash.expect("ErrorCreatingLot").get("error").get("id").asText());
            ash.send("CreateLotWithArchetypes", Map.of("forTrade", Map.of(ONIX, 1), "toReceive", Map.of(PIKA, 1)), 2);
            ash.expect("ErrorCreatingLot");
            ash.send("CreateLotWithArchetypes", Map.of("forTrade", Map.of(PIKA, 1), "toReceive", Map.of()), 3);
            assertEquals("trade.errorCreatingLotDialog.body", ash.expect("ErrorCreatingLot").get("error").get("id").asText());

            // Public lot; the receiver lacks the requested card.
            ash.send("CreateLotWithArchetypes", Map.of("forTrade", List.of(PIKA), "toReceive", List.of(ONIX, ONIX)), 4);
            String lotId = ash.expect("LotCreated").get("lotID").asText();
            misty.send("ViewAllPublicTradesCount", Map.of(), 5);
            assertEquals(1, misty.expect("LotsRetrievedCount").get("count").asInt());
            ash.send("ViewAllPublicTradesCount", Map.of(), 6);
            assertEquals(0, ash.expect("LotsRetrievedCount").get("count").asInt());
            misty.send("SearchForLots", Map.of("archetypes", List.of(PIKA.toUpperCase())), 7);
            assertEquals(1, misty.expect("LotsRetrieved").get("lots").size());
            misty.send("SearchForLots", Map.of("archetypes", List.of(ONIX)), 8);
            assertEquals(0, misty.expect("LotsRetrieved").get("lots").size());

            misty.send("AcceptTrade", Map.of("lotID", lotId), 9);
            assertEquals("trade.createLot.notEnoughCards.body", misty.expect("ErrorPurchasingLot").get("error").get("id").asText());
            // Own lot cannot be accepted.
            ash.send("AcceptTrade", Map.of("lotID", lotId), 10);
            ash.expect("ErrorPurchasingLot");
            assertEquals(Map.of(PIKA, 1, ONIX, 0), tradable(players, ash.accountId));

            // The sender sold the card elsewhere: accepting cancels the lot.
            players.removeFromCollection(ash.accountId, PIKA, 1, true);
            players.addToCollection(misty.accountId, Map.of(ONIX, 2), true);
            misty.send("AcceptTrade", Map.of("lotID", lotId), 11);
            misty.expect("ErrorPurchasingLot");
            assertEquals("cancelled", new TradeRepository(players.database()).get(lotId).orElseThrow().status());
            assertEquals(Map.of(ONIX, 2), tradable(players, misty.accountId));
        }
    }

    @Test
    void cancelAndDecline() throws Exception {
        try (SocialTestSupport env = env(); Client ash = env.login("Ash"); Client misty = env.login("Misty")) {
            PlayerRepository players = env.services.players();
            players.addToCollection(ash.accountId, Map.of(PIKA, 2), true);
            TradeRepository repo = new TradeRepository(players.database());

            ash.send("CreateLotWithArchetypes", Map.of("forTrade", Map.of(PIKA, 1), "toReceive", Map.of(ONIX, 1),
                    "privateTo", misty.accountId), 1);
            String a = ash.expect("LotCreated").get("lotID").asText();
            misty.expect("LotCreatedForYou");
            ash.send("CreateLotWithArchetypes", Map.of("forTrade", Map.of(PIKA, 1), "toReceive", Map.of(ONIX, 1),
                    "privateTo", TradeHandler.EMPTY_GUID), 2);
            String b = ash.expect("LotCreated").get("lotID").asText();
            assertNull(repo.get(b).orElseThrow().recipientId(), "nil-guid privateTo means public");

            misty.send("RemoveLot", Map.of("lotID", a), 3); // private recipient declines
            JsonNode removed = misty.expect("LotRemoved");
            assertEquals("Removed", removed.get("reason").asText());
            assertEquals("declined", repo.get(a).orElseThrow().status());
            misty.send("RemoveLot", Map.of("lotID", b), 4); // stranger cannot remove a public lot
            assertEquals("That trade offer could not be removed. Please try again.",
                    misty.expect("ErrorRemovingLot").get("error").get("id").asText());
            ash.send("RemoveLot", Map.of("lotID", b), 5);
            ash.expect("LotRemoved");
            assertEquals("cancelled", repo.get(b).orElseThrow().status());
            ash.send("RemoveLot", Map.of("lotID", b), 6);
            ash.expect("ErrorRemovingLot");
            ash.send("ViewMyLotsWithPagination", Map.of("offset", 0, "limit", 50), 7);
            assertEquals(0, ash.expect("MyLotsRetrieved").get("lots").size());
        }
    }

    @Test
    void flagsAndPrices() throws Exception {
        try (SocialTestSupport env = env(); Client ash = env.login("Ash")) {
            ash.send("SetArchetypeForTradeCount", Map.of("archetypeID", PIKA, "forTrade", 2), 1);
            JsonNode set = ash.expect("ArchetypeForTradeSet");
            assertEquals(2, set.get("forTrade").asInt());
            ash.send("SetArchetypeReview", Map.of("archetypeID", PIKA, "review", 1), 2);
            assertEquals(1, ash.expect("ArchetypeReviewSet").get("review").asInt());
            var flags = env.services.players().flags(ash.accountId);
            assertEquals(1, flags.size());
            assertEquals(2, flags.get(0).forTrade());
            assertEquals(1, flags.get(0).review());
            ash.send("GetCreateLotPrices", Map.of(), 3);
            JsonNode prices = ash.expect("CreateLotPrices").get("prices");
            assertEquals(-1784319558L, prices.get("0").get("name").asLong());
            assertEquals(48, prices.get("2").get("value").asInt());
        }
    }

    @Test
    void concurrentAcceptsExactlyOneWins() throws Exception {
        try (SocialTestSupport env = env(); Client ash = env.login("Ash")) {
            PlayerRepository players = env.services.players();
            TradeRepository repo = new TradeRepository(players.database());
            players.addToCollection(ash.accountId, Map.of(PIKA, 1), true);
            String[] buyers = new String[6];
            for (int i = 0; i < buyers.length; i++) {
                buyers[i] = env.services.accounts().create("buyer" + i, "pw", false).orElseThrow().accountId();
                players.addToCollection(buyers[i], Map.of(ONIX, 1), true);
            }
            String lot = repo.create(ash.accountId, Map.of(PIKA, 1), Map.of(ONIX, 1), null).offer().offerId();
            ExecutorService pool = Executors.newFixedThreadPool(buyers.length);
            try {
                List<Future<TradeRepository.Result>> fs = new java.util.ArrayList<>();
                for (String b : buyers) fs.add(pool.submit(() -> repo.accept(lot, b)));
                int wins = 0;
                for (Future<TradeRepository.Result> f : fs) if (f.get().error() == null) wins++;
                assertEquals(1, wins);
            } finally {
                pool.shutdownNow();
            }
            int ashOnix = tradable(players, ash.accountId).getOrDefault(ONIX, 0);
            int ashPika = tradable(players, ash.accountId).getOrDefault(PIKA, 0);
            assertEquals(1, ashOnix);
            assertEquals(0, ashPika);
            int totalOnix = 0, totalPika = 0;
            for (String b : buyers) {
                totalOnix += tradable(players, b).getOrDefault(ONIX, 0);
                totalPika += tradable(players, b).getOrDefault(PIKA, 0);
            }
            assertEquals(buyers.length - 1, totalOnix);
            assertEquals(1, totalPika);
        }
    }
}
