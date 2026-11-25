package com.ptcgo.revival.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.catalog.Catalog.Card;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.PlayerRepository;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in: mvn test -Dcatalog.dir=/path/to/export (needs cards/, products.json and shop_defaults.json). */
@EnabledIfSystemProperty(named = "catalog.dir", matches = ".+")
class FullExportShopTest {
    static Path dir;
    static Catalog catalog;
    static ShopProducts products;

    @BeforeAll
    static void load() throws Exception {
        dir = Path.of(System.getProperty("catalog.dir"));
        catalog = Catalog.load(dir);
        products = ShopProducts.load(dir);
    }

    private static ShopProduct packOf(String set) {
        return products.all().stream().filter(p -> p.isPack() && p.key().equals(set)).findFirst().orElseThrow();
    }

    private PackOpener opener(long seed) {
        return new PackOpener(catalog, products, PackRules.defaults(), PackMerges.defaults(), new Random(seed));
    }

    private static int rarity(Card c) {
        return c.intAttribute(Rarity.ATTR_RARITY, -1);
    }

    private static boolean energy(Card c) {
        return c.intAttribute(Rarity.ATTR_CARD_TYPE, -1) == Rarity.CARD_TYPE_ENERGY;
    }

    @Test
    void thousandSwsh9PacksStayInTheSetWithCorrectSlots() {
        PackOpener o = opener(2024);
        ShopProduct pack = packOf("SWSH9");
        Set<String> setCards = catalog.guidsInSet("SWSH9");
        Set<String> tg = catalog.guidsInSet("SWSH9TG"); // merged into the pack with chance 0.33
        Map<String, Integer> seen = new HashMap<>();
        int merged = 0;
        for (int i = 0; i < 1000; i++) {
            List<String> p = o.open(pack);
            assertEquals(10, p.size());
            for (int s = 0; s < 10; s++) {
                String g = p.get(s);
                Card c = catalog.card(g);
                assertNotNull(c, g);
                if (s == 8 && tg.contains(g)) {
                    merged++;
                    assertFalse(energy(c));
                    continue;
                }
                assertTrue(setCards.contains(g) || (s == 3 && c.key().equals("SVE")) || (s == 3 && c.key().equals("Free_Energy")),
                        "slot " + s + " card outside the set: " + c.key() + " " + c.displayName());
                seen.merge(g, 1, Integer::sum);
                if (s < 3) assertEquals(Rarity.COMMON, rarity(c));
                if (s >= 4 && s < 8) assertEquals(Rarity.UNCOMMON, rarity(c));
                if (s == 9) assertTrue(rarity(c) >= Rarity.RARE || energy(c));
            }
        }
        assertTrue(merged > 250 && merged < 420, "trainer gallery inserts ~33%: " + merged);
        // every plain Common and Uncommon of the set is reachable
        for (String g : setCards) {
            Card c = catalog.card(g);
            if (!energy(c) && (rarity(c) == Rarity.COMMON || rarity(c) == Rarity.UNCOMMON)) assertTrue(seen.containsKey(g), "never drawn: " + c.displayName() + " #" + c.collectorNumber());
        }
        System.out.printf("SWSH9: %d of %d cards drawn in 1000 packs, %d gallery inserts%n", seen.size(), setCards.size(), merged);
    }

    @Test
    void base1PacksHaveElevenCardsAndScarletVioletBorrowsSveEnergy() {
        PackOpener o = opener(5);
        for (int i = 0; i < 200; i++) {
            List<String> p = o.open(packOf("BASE1"));
            assertEquals(11, p.size());
            for (String g : p) assertEquals("BASE1", catalog.card(g).key());
        }
        for (int i = 0; i < 200; i++) {
            List<String> p = o.open(packOf("SV1"));
            assertEquals(10, p.size());
            assertEquals("SVE", catalog.card(p.get(3)).key(), "SV1 prints no basics; its energy slot comes from SVE");
        }
    }

    @Test
    void me55AlwaysHoldsExactlyOnePikachuLast() {
        PackOpener o = opener(11);
        for (int i = 0; i < 300; i++) {
            List<String> p = o.open(packOf("ME55"));
            // ME55C (Classic Collection) can replace the reverse-holo slot after the rules ran, as in Python
            long pikachu = p.stream().filter(g -> catalog.card(g).key().equals("ME55")
                    && catalog.card(g).displayName().strip().equals("Pikachu")).count();
            assertEquals(1, pikachu);
            assertEquals("Pikachu", catalog.card(p.get(p.size() - 1)).displayName().strip());
        }
    }

    @Test
    void everyEligibleSetOpensWithoutError() {
        PackOpener o = opener(1);
        int opened = 0;
        for (ShopProduct p : products.all()) {
            if (!p.isPack() || p.guid().equals(PackOpener.COIN_BOOSTER_GUID)) continue;
            if (catalog.guidsInSet(p.key()).isEmpty()) continue; // no cards: Python falls back to its fixed default pool
            List<String> pack = o.open(p);
            assertFalse(pack.isEmpty(), p.key());
            for (String g : pack) assertNotNull(catalog.card(g), p.key() + " " + g);
            opened++;
        }
        assertTrue(opened > 150);
    }

    @Test
    void coinChestOnRealCoins() {
        ShopProduct chest = products.byGuid(PackOpener.COIN_BOOSTER_GUID);
        assertNotNull(chest);
        List<String> coins = opener(3).open(chest);
        assertEquals(2, coins.size());
        assertNotEquals(coins.get(0), coins.get(1));
        for (String g : coins) assertEquals(ShopProduct.TYPE_COINS, products.byGuid(g).productType());
    }

    @Test
    void shopListingFromRealExport() throws Exception {
        Database db = Fixtures.db();
        ShopRepository repo = new ShopRepository(db);
        repo.ensureDefaultShopItems(ShopRepository.loadDefaultOffers(dir));
        assertEquals(3, repo.listShopItems(false).size());
        ShopManager mgr = new ShopManager(products, catalog, repo, PackMerges.defaults(), "http://h/placeholder.png");
        ShopManager.Shop shop = mgr.current();
        assertTrue(shop.listing(ShopProduct.skuGuid(packOf("SWSH9").guid())) != null);
        assertNull(shop.listing(ShopProduct.skuGuid(packOf("Promo_SWSH").guid())), "promos are not sold");
        assertNull(shop.listing(ShopProduct.skuGuid(packOf("SWSH9TG").guid())), "galleries are not sold");
        // Coin Chest, Dragapult deck and the Charizard bundle come from the seeded rows
        String chest = ShopProduct.skuGuid(PackOpener.COIN_BOOSTER_GUID);
        assertEquals(1, shop.listing(chest).prices().get(0).value());
        assertEquals(500, shop.listing(ShopProduct.skuGuid("7b51ee25-fbfb-5bcd-97f9-618e7b9e7acb")).prices().get(0).value());
        assertEquals(200, shop.listing(ShopProduct.skuGuid("e29e9ec0-2dbb-5118-822d-7fa14211cb1e")).prices().get(0).value());
        assertEquals(3, shop.featuredJson().size());
        // release-rank set numbers: BASE1 is the oldest
        ShopProduct base = shop.listing(ShopProduct.skuGuid(packOf("BASE1").guid()));
        assertEquals(1, base.attribute(ShopProduct.ATTR_SET_NUMBER).asInt());
        System.out.printf("Shop lists %d products%n", shop.available().size());
    }

    @Test
    void starterContentOnRealCatalogue() throws Exception {
        Database db = Fixtures.db();
        StarterContent starter = new StarterContent(db, catalog, products, PackMerges.defaults());
        String acct = Fixtures.account(db, "ash");
        assertTrue(starter.grant(acct));
        PlayerRepository players = new PlayerRepository(db);
        var decks = players.decks(acct, false);
        assertEquals(4, decks.size());
        for (var d : decks) assertEquals(60, d.deckData().path("piles").path("deck").size(), d.name());
        var packs = starter.starterTradablePacks();
        assertEquals(15, packs.size());
        long tradablePacks = players.collection(acct).stream().filter(r -> r.tradable() == 2).count();
        assertEquals(15, tradablePacks);
    }
}
