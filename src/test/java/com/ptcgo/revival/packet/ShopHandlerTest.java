package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.db.Account;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.shop.CodeRepository;
import com.ptcgo.revival.shop.Currency;
import com.ptcgo.revival.shop.Fixtures;
import com.ptcgo.revival.shop.ShopModule;
import com.ptcgo.revival.shop.ShopProduct;
import com.ptcgo.revival.shop.ShopProducts;
import com.ptcgo.revival.shop.ShopRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShopHandlerTest {
    static final String PACK = "11111111-1111-4111-8111-111111111111";
    static final String DECK = "d0000000-0000-4000-8000-000000000001";
    static final String BUNDLE = "b0000000-0000-4000-8000-000000000001";

    private Database db;
    private Catalog catalog;
    private ShopModule module;
    private PlayerRepository players;
    private Account ash;
    private String acct;
    private ShopHandler shop;

    @BeforeEach
    void setUp() throws Exception {
        db = Fixtures.db();
        catalog = Catalog.of(Fixtures.smallSet("TST"));
        String c1 = catalog.cards().iterator().next().guid();
        ShopProduct deck = Fixtures.product("""
                {"guid":"%s","key":"TST","kind":"deck","product_type":12,"name":"Test Deck",
                 "attributes":{"10140":{"type":"json","value":"{\\"id\\": \\"Test Deck\\"}"},"10540":{"type":"int","value":12}},
                 "deck":{"contents":["%s","%s","%s"],"is_theme_deck":true,"theme_legal":true}}""".formatted(DECK, c1, c1, c1));
        ShopProduct bundle = Fixtures.product("""
                {"guid":"%s","key":"TST","kind":"bundle","product_type":21,"name":"Big Bundle",
                 "attributes":{"10140":{"type":"json","value":"{\\"id\\": \\"Big Bundle\\"}"},"10540":{"type":"int","value":21}},
                 "bundle":{"grants":["%s","%s"],"tokens":1000,"once_per_account":true}}""".formatted(BUNDLE, PACK, c1));
        ShopProducts products = new ShopProducts(List.of(Fixtures.pack(PACK, "TST"), deck, bundle));
        ShopRepository repo = new ShopRepository(db);
        repo.upsertShopItem(DECK, new ShopRepository.ItemUpdate("Test Deck", Currency.TRAINER_TOKENS, 200L, true, true, true, 10, null));
        repo.upsertShopItem(BUNDLE, new ShopRepository.ItemUpdate("Big Bundle", Currency.TRAINER_TOKENS, 500L, true, true, true, 5, null));
        module = ShopModule.create(db, catalog, products, repo, "http://h/placeholder.png", new Random(1));
        shop = module.shopHandler();
        players = new PlayerRepository(db);
        ash = new com.ptcgo.revival.db.AccountRepository(db).create("ash", "pw", false).orElseThrow();
        acct = ash.accountId();
        players.wallet(acct);
    }

    private FakeClient client(boolean loggedIn) {
        FakeClient c = new FakeClient(db, catalog);
        return loggedIn ? c.login(ash) : c;
    }

    private void call(PacketHandler h, FakeClient c, String name, Object payload, int rid) throws Exception {
        h.handlers().get(name).handle(c, com.ptcgo.revival.net.WargCodec.JSON.valueToTree(payload), rid, 0);
    }

    private String sku(String productGuid) {
        return ShopProduct.skuGuid(productGuid);
    }

    private int tradable(String guid) throws Exception {
        return players.collection(acct).stream().filter(r -> r.archetypeId().equals(guid)).mapToInt(PlayerRepository.CollectionRow::tradable).sum();
    }

    // ---- listings --------------------------------------------------------------------------------

    @Test
    void availableProductsListsTokensAutoPackAndShopRowsUnderSkus() throws Exception {
        FakeClient c = client(true);
        call(shop, c, "GetAvailableProducts", Map.of(), 7);
        assertEquals(7, c.sent.get(0).requestId());
        JsonNode v = c.value(0);
        List<String> guids = new ArrayList<>();
        v.get("availableProducts").forEach(g -> guids.add(g.asText()));
        // tokens first, then the automatic booster, then shop rows by sort order (bundle 5, deck 10)
        assertEquals(List.of("00000000-0000-0000-0000-000000000002", sku(PACK), sku(BUNDLE), sku(DECK)), guids);
        assertTrue(guids.contains(sku(PACK)));
        assertTrue(guids.contains(sku(DECK)));
        assertTrue(guids.contains(sku(BUNDLE)));
        assertFalse(guids.contains(PACK), "real guids are never listed");
        // auto-priced booster: 200 tokens, price map shape
        JsonNode price = v.get("priceMap").get(sku(PACK));
        assertEquals(77381929, price.get("name").asLong());
        assertEquals(Currency.TRAINER_TOKENS, price.get("value").get(0).get("name").asLong());
        assertEquals(200, price.get("value").get(0).get("value").asInt());
        assertEquals(500, v.get("priceMap").get(sku(BUNDLE)).get("value").get(0).get("value").asInt());
        assertEquals(0, v.get("priceMap").get("00000000-0000-0000-0000-000000000002").get("value").get(0).get("value").asInt());
        assertEquals("Big Bundle", v.get("archetypeProperties").get(sku(BUNDLE)).get("bannerText").get("id").asText());
        assertEquals("http://h/placeholder.png", v.get("archetypeProperties").get(sku(BUNDLE)).get("bannerImage").asText());
        // newArchetypes carry the SKU as archetype id and catalog id, json attributes parsed
        JsonNode packArch = null;
        for (JsonNode a : v.get("newArchetypes")) if (a.get("archetypeID").asText().equals(sku(PACK))) packArch = a;
        assertNotNull(packArch);
        boolean sawCatalogId = false, sawParsedName = false;
        for (JsonNode a : packArch.get("attributes")) {
            if (a.get("name").asInt() == 10570) sawCatalogId = a.get("value").asText().equals(sku(PACK));
            if (a.get("name").asInt() == 10140) sawParsedName = a.get("value").get("id").asText().equals("TST pack");
        }
        assertTrue(sawCatalogId && sawParsedName);
    }

    @Test
    void featuredAndTopSelling() throws Exception {
        FakeClient c = client(true);
        call(shop, c, "GetFeaturedProducts", Map.of(), 1);
        JsonNode featured = c.value(0).get("products");
        assertEquals(sku(BUNDLE), featured.get(0).get("productID").asText()); // sort order 5 comes first
        assertEquals(2, featured.size());
        assertEquals(8, featured.get(0).get("urls").size());
        assertEquals(2, featured.get(0).get("urls").get("en").size());
        call(shop, c, "GetTopSellingProducts", Map.of(), 2);
        JsonNode top = c.value(1);
        assertNotNull(top.get("invoiceID"));
        assertTrue(top.get("products").size() >= 3);
        // shop rows are inserted first, the automatic booster is appended
        assertEquals(sku(DECK), top.get("products").get(0).get("archetypeID").asText());
    }

    @Test
    void themeDeckContents() throws Exception {
        FakeClient c = client(true);
        call(shop, c, "GetThemeDeckContents", Map.of(), 3);
        assertEquals(3, c.value(0).get("themeDeckContentsMap").get(DECK).size());
    }

    // ---- catalog purchases -----------------------------------------------------------------------

    @Test
    void purchaseCatalogProductsChargesGrantsAndNotifies() throws Exception {
        FakeClient c = client(true);
        call(shop, c, "PurchaseCatalogProducts", Map.of("selections", List.of(sku(PACK), sku(PACK))), 9);
        assertEquals(List.of("ProductsPurchased", "Notification", "CurrentWallet"), c.names());
        assertEquals(9, c.sent.get(0).requestId());
        assertEquals(2, c.value(0).get("items").size());
        assertEquals(PACK, c.value(0).get("items").get(0).get("archetypeID").asText()); // the real guid is granted
        assertTrue(c.value(0).get("items").get(0).get("isTradable").asBoolean());
        assertTrue(c.value(0).get("items").get(0).get("lockID").isNull());
        JsonNode note = Fixtures.json(c.value(1).get("payload").asText());
        assertEquals("ProductsFulfilled", note.get("name").asText());
        assertEquals(2, note.get("value").get("items").size());
        assertEquals(600, players.wallet(acct).coins());
        assertEquals(2, tradable(PACK));
        assertEquals(600, c.value(2).get("currencies").get(0).get("value").asInt());
    }

    @Test
    void purchaseByProductNameWhenNoSelections() throws Exception {
        FakeClient c = client(true);
        call(shop, c, "PurchaseCatalogProducts", Map.of("products", List.of("Test Deck")), 1);
        assertEquals("ProductsPurchased", c.names().get(0));
        assertEquals(1, tradable(DECK));
        assertEquals(800, players.wallet(acct).coins());
    }

    @Test
    void purchaseFailuresReplyWithPythonTextsAndResyncWallet() throws Exception {
        FakeClient signedOut = client(false);
        call(shop, signedOut, "PurchaseCatalogProducts", Map.of("selections", List.of(sku(PACK))), 4);
        assertEquals(List.of("ProductPurchaseFailure"), signedOut.names()); // no wallet to push when signed out
        assertEquals("Sign in to buy products.", signedOut.value(0).get("error").get("id").asText());

        FakeClient c = client(true);
        call(shop, c, "PurchaseCatalogProducts", Map.of("selections", List.of("not-a-listing")), 5);
        assertEquals("ProductPurchaseFailure", c.names().get(0));
        assertEquals("This product is no longer available. Please reopen the shop.", c.value(0).get("error").get("id").asText());
        assertEquals("CurrentWallet", c.names().get(1));

        FakeClient broke = client(true);
        players.updateWallet(acct, 100, 0, 100);
        call(shop, broke, "PurchaseCatalogProducts", Map.of("selections", List.of(sku(PACK))), 6);
        assertEquals("You don't have enough currency for this purchase.", broke.value(0).get("error").get("id").asText());
        assertEquals(100, players.wallet(acct).coins());
        assertEquals(0, tradable(PACK));

        FakeClient empty = client(true);
        call(shop, empty, "PurchaseCatalogProducts", Map.of(), 7);
        assertEquals("This product is no longer available. Please reopen the shop.", empty.value(0).get("error").get("id").asText());
    }

    @Test
    void bundleIsLimitedToOnePerAccountAndCreditsTokens() throws Exception {
        FakeClient c = client(true);
        call(shop, c, "PurchaseCatalogProducts", Map.of("selections", List.of(sku(BUNDLE))), 1);
        assertEquals("ProductsPurchased", c.names().get(0));
        assertEquals(1500, players.wallet(acct).coins()); // 1000 - 500 + 1000
        assertEquals(1, tradable(PACK));
        FakeClient again = client(true);
        call(shop, again, "PurchaseCatalogProducts", Map.of("selections", List.of(sku(BUNDLE))), 2);
        assertTrue(again.value(0).get("error").get("id").asText().startsWith("You already have this item."));
        assertEquals(1500, players.wallet(acct).coins());
        FakeClient twice = client(true);
        call(shop, twice, "PurchaseCatalogProducts", Map.of("selections", List.of(sku(BUNDLE), sku(BUNDLE))), 3);
        assertEquals("ProductPurchaseFailure", twice.names().get(0));
    }

    @Test
    void bundleThroughOtherPathsUsesTheSameAtomicPurchase() throws Exception {
        FakeClient c = client(true);
        call(shop, c, "PurchaseAndOpenProducts", Map.of("products", List.of(sku(BUNDLE))), 1);
        assertEquals(List.of("Notification", "CurrentWallet", "CollectionCountFound"), c.names());
        assertEquals("ProductsOpened", Fixtures.json(c.value(0).get("payload").asText()).get("name").asText());
        assertEquals(1500, players.wallet(acct).coins());

        FakeClient again = client(true);
        call(shop, again, "PurchaseAndOpenProducts", Map.of("products", List.of(sku(BUNDLE))), 2);
        assertEquals("ProductsOpenedFailure", again.names().get(0));
        assertTrue(again.value(0).get("error").get("id").asText().startsWith("You already have this item."));

        FakeClient arch = client(true);
        call(shop, arch, "PurchaseArchetypes", Map.of("archetypes", Map.of(sku(BUNDLE), 1)), 3);
        assertEquals("ArchetypesPurchaseFailure", arch.names().get(0));
        FakeClient out = client(false);
        call(shop, out, "PurchaseArchetypes", Map.of("archetypes", Map.of(sku(BUNDLE), 1)), 4);
        assertEquals("Sign in to buy products.", out.value(0).get("error").get("id").asText());
    }

    // ---- purchase and open ------------------------------------------------------------------------

    @Test
    void purchaseAndOpenPackRevealsTenCardsAndNeverStoresThePack() throws Exception {
        FakeClient c = client(true);
        call(shop, c, "PurchaseAndOpenProducts", Map.of("products", List.of(sku(PACK)), "isTradable", true), 11);
        assertEquals(List.of("ProductsOpened", "CollectionCountFound", "CurrentWallet"), c.names());
        assertEquals(11, c.sent.get(0).requestId());
        JsonNode v = c.value(0);
        assertEquals(acct, v.get("accountID").asText());
        assertEquals(10, v.get("items").size());
        assertEquals(1, v.get("products").size());
        assertEquals(PACK, v.get("products").get(0).get("archetypeID").asText());
        assertTrue(v.get("additionalData").isObject());
        for (JsonNode item : v.get("items")) {
            assertNotNull(catalog.card(item.get("archetypeID").asText()));
            assertTrue(item.get("isTradable").asBoolean());
        }
        assertEquals(800, players.wallet(acct).coins());
        assertEquals(0, tradable(PACK));
        int total = players.collection(acct).stream().mapToInt(r -> r.tradable() + r.nontradable()).sum();
        assertEquals(10, total);
        assertEquals(800, c.value(2).get("currencies").get(0).get("value").asInt());
    }

    @Test
    void purchaseAndOpenWithoutFundsStaysSilentLikePython() throws Exception {
        players.updateWallet(acct, 5, 0, 0);
        FakeClient c = client(true);
        call(shop, c, "PurchaseAndOpenProducts", Map.of("products", List.of(sku(PACK))), 1);
        assertTrue(c.sent.isEmpty());
        assertEquals(5, players.wallet(acct).coins());
        // unknown / unlisted ids are dropped, not charged
        call(shop, c, "PurchaseAndOpenProducts", Map.of("products", List.of(PACK)), 2); // real guid is not a listing
        assertEquals(List.of("Notification", "CollectionCountFound", "CurrentWallet"), c.names()); // nothing opened, nothing charged
        assertEquals(5, players.wallet(acct).coins());
    }

    @Test
    void openOwnedPackByArchetypeId() throws Exception {
        players.addToCollection(acct, Map.of(PACK, 1), true);
        FakeClient c = client(true);
        call(shop, c, "OpenProductsByArchetypeID", Map.of("products", List.of(PACK)), 3);
        assertEquals(List.of("ProductsOpened", "CollectionCountFound"), c.names());
        assertEquals(10, c.value(0).get("items").size());
        assertEquals(0, tradable(PACK));
        assertEquals(800 + 200, players.wallet(acct).coins()); // opening an owned pack is free

        FakeClient again = client(true);
        call(shop, again, "OpenProductsByArchetypeID", Map.of("products", List.of(PACK)), 4);
        assertEquals(List.of("ProductsOpenedFailure"), again.names());
        assertEquals("Unable to open these products. Please try again.", again.value(0).get("error").get("id").asText());
        FakeClient unknown = client(true);
        call(shop, unknown, "OpenProductsByArchetypeID", Map.of("products", List.of("zzz")), 5);
        assertEquals("ProductsOpenedFailure", unknown.names().get(0));
        FakeClient out = client(false);
        call(shop, out, "OpenProductsByArchetypeID", Map.of("products", List.of(PACK)), 6);
        assertEquals("ProductsOpenedFailure", out.names().get(0));
    }

    @Test
    void openingADeckProductSavesItAndSendsDeckListAndNotification() throws Exception {
        players.addToCollection(acct, Map.of(DECK, 1), true);
        FakeClient c = client(true);
        call(shop, c, "OpenProductsByArchetypeID", Map.of("products", List.of(DECK)), 3);
        assertEquals("OnlineDecksFound", c.names().get(0));
        assertEquals(1, c.value(0).get("decks").size());
        assertEquals("Test Deck", c.value(0).get("decks").get(0).get("deckName").asText());
        assertTrue(c.names().contains("Notification"));
        assertEquals(1, players.decks(acct, false).size());
    }

    // ---- legacy archetype purchase ----------------------------------------------------------------

    @Test
    void purchaseArchetypesGrantsTradablePacksAndPushes() throws Exception {
        FakeClient c = client(true);
        call(shop, c, "PurchaseArchetypes", Map.of("archetypes", Map.of(sku(PACK), 3, "bogus", 2, sku(DECK), -1)), 5);
        assertEquals(List.of("ArchetypesPurchased", "CollectionCountFound", "CurrentWallet"), c.names());
        assertEquals(3, c.value(0).get("items").size());
        assertNotNull(c.value(0).get("transactionID"));
        assertEquals(3, tradable(PACK));
        assertEquals(400, players.wallet(acct).coins());

        players.updateWallet(acct, 100, 0, 0);
        FakeClient poor = client(true);
        call(shop, poor, "PurchaseArchetypes", Map.of("archetypes", Map.of(sku(PACK), 1)), 6);
        assertTrue(poor.sent.isEmpty());
        assertEquals(3, tradable(PACK));
    }

    // ---- redemption -------------------------------------------------------------------------------

    @Test
    void redemptionHandlerValidateAndRedeem() throws Exception {
        var redemption = module.redemptionHandler();
        module.codes.create("WELCOME", Fixtures.json("{\"products\":{\"" + PACK + "\":1},\"coins\":10}"), 2, true);

        FakeClient out = client(false);
        call(redemption, out, "ValidateCode", Map.of("code", "welcome"), 1);
        assertEquals("InvalidCode", out.names().get(0));
        assertEquals("shop.redeemcodes.invalidcode", out.value(0).get("reason").get("id").asText());
        call(redemption, out, "RedeemCodes", Map.of("codes", List.of("a", "b")), 2);
        assertEquals(List.of("InvalidCode", "CodeRedemptionFailure", "CodeRedemptionFailure"), out.names());
        assertEquals("a", out.value(1).get("code").asText()); // signed-out failures echo the code untouched

        FakeClient c = client(true);
        call(redemption, c, "ValidateCode", Map.of("code", " welcome "), 3);
        assertEquals("CodeIsValid", c.names().get(0));
        assertEquals("WELCOME", c.value(0).get("code").asText());
        assertEquals("WELCOME", c.value(0).get("couponGroup").asText());
        assertEquals(List.of(PACK), List.of(c.value(0).get("products").get(0).asText()));
        assertTrue(c.value(0).has("maxRedemptionsForGroup") && c.value(0).get("maxRedemptionsForGroup").isNull());

        FakeClient r = client(true);
        call(redemption, r, "RedeemCodes", Map.of("codes", List.of("welcome", "nope", "WELCOME")), 4);
        assertEquals(List.of("CodeSuccessfullyRedeemed", "CodeRedemptionFailure", "CodeRedemptionFailure",
                "CollectionCountFound", "CurrentWallet"), r.names());
        assertEquals("WELCOME", r.value(0).get("code").asText());
        assertEquals("shop.redeemcodes.invalidcode", r.value(1).get("reason").get("id").asText());
        assertEquals(CodeRepository.CODE_USED, r.value(2).get("reason").get("id").asText());
        assertEquals(0, r.sent.get(3).requestId());
        assertEquals(1010, players.wallet(acct).coins());
        assertEquals(1, players.collection(acct).stream().filter(x -> x.archetypeId().equals(PACK)).mapToInt(PlayerRepository.CollectionRow::nontradable).sum());

        FakeClient v = client(true);
        call(redemption, v, "ValidateCode", Map.of("code", "WELCOME"), 5);
        assertEquals(CodeRepository.CODE_USED, v.value(0).get("reason").get("id").asText());
        FakeClient none = client(true);
        call(redemption, none, "RedeemCodes", Map.of("codes", List.of("zzz")), 6);
        assertEquals(List.of("CodeRedemptionFailure"), none.names()); // no sync without a success
    }
}
