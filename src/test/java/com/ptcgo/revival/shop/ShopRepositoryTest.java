package com.ptcgo.revival.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.PlayerRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShopRepositoryTest {
    private Database db;
    private ShopRepository repo;
    private PlayerRepository players;
    private String acct;

    @BeforeEach
    void setUp() throws Exception {
        db = Fixtures.db();
        repo = new ShopRepository(db);
        players = new PlayerRepository(db);
        acct = Fixtures.account(db, "ash");
        players.wallet(acct); // 1000 / 0 / 100
    }

    private int tradable(String guid) throws Exception {
        return players.collection(acct).stream().filter(r -> r.archetypeId().equals(guid)).mapToInt(PlayerRepository.CollectionRow::tradable).sum();
    }

    private int nontradable(String guid) throws Exception {
        return players.collection(acct).stream().filter(r -> r.archetypeId().equals(guid)).mapToInt(PlayerRepository.CollectionRow::nontradable).sum();
    }

    @Test
    void migrateIsIdempotentAndCreatesPythonCompatibleTables() throws Exception {
        db.migrate();
        try (Connection c = db.connect(); var st = c.createStatement()) {
            for (String t : List.of("shop_items", "redemption_codes", "code_redemption_entries", "product_claims")) {
                try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='" + t + "'")) {
                    assertTrue(rs.next(), t);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE name='product_claims'")) {
                rs.next();
                assertTrue(rs.getString(1).contains("uq_product_claim"));
            }
        }
    }

    @Test
    void defaultOffersSeedOnceAndRepriceOnlyShippedPrices() throws Exception {
        var offers = List.of(
                new ShopRepository.DefaultOffer("G-DECK", "Deck", 200, 10, List.of()),
                new ShopRepository.DefaultOffer("G-BUNDLE", "Bundle", 500, 5, List.of(1500L)));
        repo.ensureDefaultShopItems(offers);
        repo.ensureDefaultShopItems(offers); // idempotent
        var items = repo.listShopItems(false);
        assertEquals(2, items.size());
        assertEquals("G-BUNDLE", items.get(0).productGuid()); // sort_order 5 first
        assertTrue(items.get(0).enabled() && items.get(0).featured() && items.get(0).topSelling());
        assertEquals(Currency.TRAINER_TOKENS, items.get(0).currency());

        // an admin price survives re-seeding; a previously shipped price moves
        repo.upsertShopItem("G-DECK", new ShopRepository.ItemUpdate(null, null, 350L, null, null, null, null, null));
        repo.upsertShopItem("G-BUNDLE", new ShopRepository.ItemUpdate(null, null, 1500L, null, null, null, null, null));
        repo.ensureDefaultShopItems(offers);
        var after = repo.listShopItems(false);
        assertEquals(500, after.get(0).price());
        assertEquals(350, after.get(1).price());
        assertEquals(2, repo.listShopItems(true).size());
        repo.upsertShopItem("G-DECK", new ShopRepository.ItemUpdate(null, null, null, false, null, null, null, null));
        assertEquals(1, repo.listShopItems(true).size());
        assertTrue(repo.deleteShopItem(after.get(1).id()));
    }

    @Test
    void purchaseDebitsGrantsTradableAndClaims() throws Exception {
        String err = repo.purchase(acct, Map.of(Currency.TRAINER_TOKENS, 300L), Map.of("pack-a", 2), Map.of(), List.of("Bundle-1"));
        assertNull(err);
        assertEquals(700, players.wallet(acct).coins());
        assertEquals(2, tradable("pack-a"));
        assertTrue(repo.hasClaim(acct, "bundle-1"));

        // a second purchase of the same limited product is refused and changes nothing
        assertEquals(ShopRepository.PURCHASE_ERROR_ONCE,
                repo.purchase(acct, Map.of(Currency.TRAINER_TOKENS, 100L), Map.of("pack-a", 1), Map.of(), List.of("bundle-1")));
        assertEquals(700, players.wallet(acct).coins());
        assertEquals(2, tradable("pack-a"));
        // the same claim twice in one request is refused too
        assertEquals(ShopRepository.PURCHASE_ERROR_ONCE,
                repo.purchase(acct, Map.of(), Map.of(), Map.of(), List.of("x", "X")));
    }

    @Test
    void purchaseRefusesWhenBrokeOrWrongCurrencyAndRollsBack() throws Exception {
        assertEquals(ShopRepository.PURCHASE_ERROR_FUNDS,
                repo.purchase(acct, Map.of(Currency.TRAINER_TOKENS, 1001L), Map.of("p", 1), Map.of(), List.of()));
        assertEquals(ShopRepository.PURCHASE_ERROR_FUNDS,
                repo.purchase(acct, Map.of(Currency.GEMS, 1L), Map.of("p", 1), Map.of(), List.of()));
        assertEquals(ShopRepository.PURCHASE_ERROR_CURRENCY,
                repo.purchase(acct, Map.of(12345L, 1L), Map.of("p", 1), Map.of(), List.of()));
        assertEquals(ShopRepository.PURCHASE_ERROR_CURRENCY,
                repo.purchase(acct, Map.of(Currency.TRAINER_TOKENS, 10L), Map.of("p", 1), Map.of(99L, 5L), List.of("c")));
        assertEquals(new PlayerRepository.Wallet(1000, 0, 100), players.wallet(acct));
        assertTrue(players.collection(acct).isEmpty());
        assertFalse(repo.hasClaim(acct, "c"));
        // mixed currencies pay independently
        assertNull(repo.purchase(acct, Map.of(Currency.TRAINER_TOKENS, 10L, Currency.EVENT_TICKETS, 100L), Map.of(), Map.of(), List.of()));
        assertEquals(new PlayerRepository.Wallet(990, 0, 0), players.wallet(acct));
    }

    @Test
    void bundleCreditClampsToInt32() throws Exception {
        assertNull(repo.purchase(acct, Map.of(), Map.of("g", 1), Map.of(Currency.TRAINER_TOKENS, 3_000_000_000L), List.of()));
        assertEquals(PlayerRepository.WALLET_MAX, players.wallet(acct).coins());
    }

    private ShopProduct pack(String guid) {
        return Fixtures.pack(guid, "TST");
    }

    @Test
    void openingConsumesNontradableFirstAndGrantsSameKind() throws Exception {
        players.addToCollection(acct, Map.of("p1", 1), true);
        players.addToCollection(acct, Map.of("p1", 1), false);
        ShopProduct p = pack("p1");
        var out = repo.openProducts(acct, List.of(p), false, null, Map.of(), x -> List.of("c1", "c2"));
        assertNull(out.error());
        assertFalse(out.opened().get(0).tradable()); // had a non-tradable copy, so it is opened first
        assertEquals(1, tradable("p1"));
        assertEquals(0, nontradable("p1"));
        assertEquals(1, nontradable("c1"));
        // now only a tradable copy is left; opening yields tradable cards, and the override wins
        out = repo.openProducts(acct, List.of(p), false, null, Map.of(), x -> List.of("c1"));
        assertTrue(out.opened().get(0).tradable());
        assertEquals(1, tradable("c1"));
        players.addToCollection(acct, Map.of("p1", 1), false); // the override also picks which copy is consumed
        out = repo.openProducts(acct, List.of(p), false, Boolean.FALSE, Map.of(), x -> List.of("c9"));
        assertFalse(out.opened().get(0).tradable());
    }

    @Test
    void openingMoreThanOwnedFailsAtomically() throws Exception {
        players.addToCollection(acct, Map.of("p1", 1), true);
        ShopProduct p = pack("p1");
        assertThrows(IllegalArgumentException.class,
                () -> repo.openProducts(acct, List.of(p, p), false, null, Map.of(), x -> List.of("c1")));
        assertEquals(1, tradable("p1"));
        assertEquals(0, tradable("c1"));
        assertThrows(IllegalArgumentException.class,
                () -> repo.openProducts(acct, List.of(pack("nope")), false, null, Map.of(), x -> List.of()));
    }

    @Test
    void purchaseAndOpenChargesInTheSameTransaction() throws Exception {
        ShopProduct p = pack("p1");
        var out = repo.openProducts(acct, List.of(p), true, null, Map.of(Currency.TRAINER_TOKENS, 200L), x -> List.of("c1"));
        assertNull(out.error());
        assertEquals(800, players.wallet(acct).coins());
        assertEquals(1, tradable("c1"));
        assertEquals(0, tradable("p1")); // purchased-and-opened packs are never stored
        // cannot pay: nothing changes
        out = repo.openProducts(acct, List.of(p), true, null, Map.of(Currency.TRAINER_TOKENS, 5000L), x -> List.of("c2"));
        assertEquals(ShopRepository.PURCHASE_ERROR_FUNDS, out.error());
        assertEquals(800, players.wallet(acct).coins());
        assertEquals(0, tradable("c2"));
        // a failing opener rolls the charge back
        assertThrows(IllegalStateException.class, () -> repo.openProducts(acct, List.of(p), true, null,
                Map.of(Currency.TRAINER_TOKENS, 200L), x -> { throw new IllegalStateException("boom"); }));
        assertEquals(800, players.wallet(acct).coins());
    }

    @Test
    void openingDeckSavesPlayableDeckOnceAndBundleCreditsTokens() throws Exception {
        ShopProduct deck = Fixtures.product("""
                {"guid":"d1","key":"SV6","kind":"deck","product_type":12,"name":"Dragapult",
                 "attributes":{"10140":{"type":"json","value":"{\\"id\\": \\"Dragapult\\"}"}},
                 "deck":{"contents":["a","a","b"],"is_theme_deck":true,"theme_legal":true}}""");
        var out = repo.openProducts(acct, List.of(deck), true, Boolean.FALSE, Map.of(), ShopProduct::contents);
        assertTrue(out.opened().get(0).tradable(), "tradable_override never applies to decks");
        assertEquals(2, tradable("a"));
        String id = ShopRepository.deckProductId(acct, "d1");
        var decks = players.decks(acct, false);
        assertEquals(1, decks.size());
        assertEquals(id, decks.get(0).id());
        assertEquals("Dragapult", decks.get(0).name());
        assertEquals(3, decks.get(0).deckData().path("piles").path("deck").size());
        assertTrue(decks.get(0).deckData().path("attributes").get(0).path("value").asBoolean());
        repo.openProducts(acct, List.of(deck), true, null, Map.of(), ShopProduct::contents);
        assertEquals(1, players.decks(acct, false).size(), "same deck product -> same deck id, no duplicate");

        ShopProduct bundle = Fixtures.product("""
                {"guid":"B-1","key":"BW1","kind":"bundle","product_type":21,"name":"B","attributes":{},
                 "bundle":{"grants":["x"],"tokens":1000,"once_per_account":true}}""");
        repo.openProducts(acct, List.of(bundle), true, null, Map.of(), ShopProduct::grants);
        assertEquals(2000, players.wallet(acct).coins());
        assertTrue(repo.hasClaim(acct, "b-1"));
    }
}
