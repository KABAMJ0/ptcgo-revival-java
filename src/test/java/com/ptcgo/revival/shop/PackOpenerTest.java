package com.ptcgo.revival.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.catalog.Catalog.Card;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PackOpenerTest {
    private static PackOpener opener(List<Card> cards, List<ShopProduct> products, PackRules rules, PackMerges merges, long seed) {
        return new PackOpener(Catalog.of(cards), new ShopProducts(products), rules, merges, new Random(seed));
    }

    private static int rarityOf(Catalog catalog, String guid) {
        return catalog.card(guid).intAttribute(Rarity.ATTR_RARITY, -1);
    }

    @Test
    void standardPackHasTenCardsInSlotOrder() {
        List<Card> cards = Fixtures.smallSet("TST");
        Catalog catalog = Catalog.of(cards);
        ShopProduct pack = Fixtures.pack("11111111-1111-4111-8111-111111111111", "TST");
        PackOpener o = new PackOpener(catalog, new ShopProducts(List.of(pack)), PackRules.none(), PackMerges.none(), new Random(1));
        for (int i = 0; i < 200; i++) {
            List<String> p = o.open(pack);
            assertEquals(10, p.size());
            // 3 commons, 1 basic energy (a common slot), 4 uncommons, reverse holo slot, hit slot
            for (int s = 0; s < 3; s++) assertEquals(0, rarityOf(catalog, p.get(s)));
            assertEquals(Rarity.CARD_TYPE_ENERGY, catalog.card(p.get(3)).intAttribute(Rarity.ATTR_CARD_TYPE, -1));
            for (int s = 4; s < 8; s++) assertEquals(1, rarityOf(catalog, p.get(s)));
            assertTrue(rarityOf(catalog, p.get(8)) <= 2, "reverse-holo slot is a common/uncommon/rare");
            assertTrue(rarityOf(catalog, p.get(9)) >= 2, "last slot is a hit");
            assertEquals(java.util.Set.of("TST"), o.setsOf(p));
        }
    }

    @Test
    void sameSeedSamePackDifferentSeedDiffers() {
        List<Card> cards = Fixtures.smallSet("TST");
        ShopProduct pack = Fixtures.pack("11111111-1111-4111-8111-111111111111", "TST");
        assertEquals(opener(cards, List.of(pack), PackRules.none(), PackMerges.none(), 7).open(pack),
                opener(cards, List.of(pack), PackRules.none(), PackMerges.none(), 7).open(pack));
        assertNotEquals(opener(cards, List.of(pack), PackRules.none(), PackMerges.none(), 7).open(pack),
                opener(cards, List.of(pack), PackRules.none(), PackMerges.none(), 8).open(pack));
    }

    @Test
    void rarityWeightsMakeUltraAndSecretRarerThanRares() {
        List<Card> cards = Fixtures.smallSet("TST");
        Catalog catalog = Catalog.of(cards);
        ShopProduct pack = Fixtures.pack("11111111-1111-4111-8111-111111111111", "TST");
        PackOpener o = new PackOpener(catalog, new ShopProducts(List.of(pack)), PackRules.none(), PackMerges.none(), new Random(3));
        Map<Integer, Integer> hits = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            List<String> p = o.open(pack);
            hits.merge(rarityOf(catalog, p.get(9)), 1, Integer::sum);
        }
        // 3 Rares x1.0, 2 Rare Holos x1.0, Ultra x0.2, Secret x0.1 -> total 5.3
        double total = 20_000;
        assertEquals(3 / 5.3, hits.get(2) / total, 0.02);
        assertEquals(2 / 5.3, hits.get(3) / total, 0.02);
        assertEquals(0.2 / 5.3, hits.get(16) / total, 0.01);
        assertEquals(0.1 / 5.3, hits.get(17) / total, 0.01);
    }

    @Test
    void packRulesSlotsAndGuaranteedPikachu() {
        // A BASE1-style rule: 7 commons, 3 uncommons, no reverse holo, 1 hit; and one guaranteed unique Pikachu.
        var rules = new PackRules(Fixtures.json("""
                {"TST":{"slots":{"common":7,"uncommon":3,"reverse_holo":0,"hit":1},
                        "guaranteed":[{"names":["Pika"],"count":1}],
                        "exclude_from_random":["Pika"],"unique_per_pack":["Pika"]}}"""), null);
        List<Card> cards = new ArrayList<>(Fixtures.smallSet("TST"));
        for (int i = 0; i < 4; i++) cards.add(Fixtures.card("TST", 100 + i, "Pika", 3, 0, false));
        Catalog catalog = Catalog.of(cards);
        ShopProduct pack = Fixtures.pack("11111111-1111-4111-8111-111111111111", "TST");
        PackOpener o = new PackOpener(catalog, new ShopProducts(List.of(pack)), rules, PackMerges.none(), new Random(5));
        for (int i = 0; i < 100; i++) {
            List<String> p = o.open(pack);
            assertEquals(11, p.size());
            long pikas = p.stream().filter(g -> catalog.card(g).displayName().equals("Pika")).count();
            assertEquals(1, pikas, "exactly one guaranteed Pika, in the last slot");
            assertEquals("Pika", catalog.card(p.get(10)).displayName());
        }
    }

    @Test
    void weightMultiplierByCardNameAndStem() {
        var rules = new PackRules(Fixtures.json("""
                {"TST":{"weight_multiplier":{"H0":0.0001,"H1_16":0.0001}}}"""), null);
        List<Card> cards = Fixtures.smallSet("TST");
        Catalog catalog = Catalog.of(cards);
        ShopProduct pack = Fixtures.pack("11111111-1111-4111-8111-111111111111", "TST");
        PackOpener o = new PackOpener(catalog, new ShopProducts(List.of(pack)), rules, PackMerges.none(), new Random(9));
        int holo = 0;
        for (int i = 0; i < 5_000; i++) if (rarityOf(catalog, o.open(pack).get(9)) == 3) holo++;
        assertTrue(holo < 20, "both holos are suppressed (by name, and by <Name>_<number> stem): " + holo);
    }

    @Test
    void mergeReplacesSecondToLastSlotWithSourceSetCard() {
        var merges = new PackMerges(Fixtures.json("""
                {"no_pack":[],"merge":{"TSTG":{"into":"TST","chance":1.0}}}"""));
        List<Card> cards = new ArrayList<>(Fixtures.smallSet("TST"));
        cards.addAll(Fixtures.smallSet("TSTG"));
        Catalog catalog = Catalog.of(cards);
        ShopProduct pack = Fixtures.pack("11111111-1111-4111-8111-111111111111", "TST");
        PackOpener o = new PackOpener(catalog, new ShopProducts(List.of(pack)), PackRules.none(), merges, new Random(2));
        for (int i = 0; i < 50; i++) {
            List<String> p = o.open(pack);
            assertEquals("TSTG", catalog.card(p.get(8)).key());
            assertEquals(java.util.Set.of("TST", "TSTG"), o.setsOf(p));
            assertNotEquals(Rarity.CARD_TYPE_ENERGY, catalog.card(p.get(8)).intAttribute(Rarity.ATTR_CARD_TYPE, -1));
        }
        assertTrue(merges.isHiddenPack("tstg"));
        assertFalse(merges.isHiddenPack("TST"));
    }

    @Test
    void unknownSetFallsBackToDefaultPool() {
        ShopProduct pack = Fixtures.pack("11111111-1111-4111-8111-111111111111", "NOPE");
        List<String> p = opener(List.of(), List.of(pack), PackRules.none(), PackMerges.none(), 1).open(pack);
        assertEquals(10, p.size());
    }

    @Test
    void sveEnergyBorrowedForSetsWithoutOwnBasics() {
        List<Card> cards = new ArrayList<>();
        for (Card c : Fixtures.smallSet("SV1")) if (c.intAttribute(Rarity.ATTR_CARD_TYPE, -1) != 3) cards.add(c);
        cards.add(Fixtures.card("SVE", 1, "Grass Energy", 0, 3, false));
        Catalog catalog = Catalog.of(cards);
        ShopProduct pack = Fixtures.pack("22222222-2222-4222-8222-222222222222", "SV1");
        PackOpener o = new PackOpener(catalog, new ShopProducts(List.of(pack)), PackRules.none(), PackMerges.none(), new Random(4));
        List<String> p = o.open(pack);
        assertEquals("SVE", catalog.card(p.get(3)).key());
    }

    @Test
    void coinChestGivesTwoDistinctNonDefaultCoinsGoldRarer() {
        List<ShopProduct> products = new ArrayList<>();
        products.add(Fixtures.coin(PackOpener.DEFAULT_COIN.toLowerCase(), "Default Coin"));
        products.add(Fixtures.coin("c0000000-0000-4000-8000-000000000001", "Gold Coin"));
        products.add(Fixtures.coin("c0000000-0000-4000-8000-000000000002", "Silver Coin"));
        products.add(Fixtures.coin("c0000000-0000-4000-8000-000000000003", "Plain Coin"));
        ShopProduct chest = Fixtures.product("""
                {"guid":"%s","key":"RewardItems","kind":"booster_pack","product_type":21,"name":"Coin Chest","attributes":{}}"""
                .formatted(PackOpener.COIN_BOOSTER_GUID));
        PackOpener o = opener(List.of(), products, PackRules.none(), PackMerges.none(), 11);
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < 4_000; i++) {
            List<String> coins = o.open(chest);
            assertEquals(2, coins.size());
            assertNotEquals(coins.get(0), coins.get(1));
            assertFalse(coins.contains(PackOpener.DEFAULT_COIN.toLowerCase()));
            coins.forEach(g -> seen.merge(g, 1, Integer::sum));
        }
        assertTrue(seen.get("c0000000-0000-4000-8000-000000000001") < seen.get("c0000000-0000-4000-8000-000000000003"));
    }

    @Test
    void bundleAndDeckOpenToTheirContents() {
        ShopProduct bundle = Fixtures.product("""
                {"guid":"b1","key":"BW1","kind":"bundle","product_type":21,"name":"B","attributes":{},
                 "bundle":{"grants":["G1","g2"],"tokens":5,"once_per_account":true}}""");
        ShopProduct deck = Fixtures.product("""
                {"guid":"d1","key":"SV6","kind":"deck","product_type":12,"name":"D","attributes":{},
                 "deck":{"contents":["a","a","b"],"is_theme_deck":true,"theme_legal":true}}""");
        PackOpener o = opener(List.of(), List.of(bundle, deck), PackRules.none(), PackMerges.none(), 1);
        assertEquals(List.of("g1", "g2"), o.open(bundle));
        assertEquals(List.of("a", "a", "b"), o.open(deck));
    }

    @Test
    void shippedRuleFilesLoad() {
        assertTrue(PackRules.defaults().hasRules("ME55"));
        assertEquals(11, PackRules.defaults().rulesFor("BASE1").path("slots").path("common").asInt() + 4);
        assertTrue(PackMerges.defaults().isHiddenPack("Promo_SWSH"));
        assertTrue(PackMerges.defaults().isHiddenPack("SWSH9TG"));
        assertEquals(1, PackMerges.defaults().sourcesFor("SWSH9").size());
        FoilRules foil = FoilRules.defaults();
        assertTrue(foil.getsFoil("RareHolo", "SWSH1", List.of()));
        assertFalse(foil.getsFoil("Common", "SWSH1", List.of()));
        assertNotNull(foil.effectFor("RareHoloV", "SWSH1", List.of("V")).name());
        // The three ME55 chase Mews resolve through pack_stems.json
        var rules = PackRules.defaults();
        Card mew = Fixtures.card("ME55", 159, "Mew", 0, 0, false);
        Card custom = new Card("68c82dfd-9afe-56e3-982f-2cbc73bd08d6", "ME55", "Mew", "ME55", 159, "pokemon", List.of(), List.of(), mew.wire());
        assertEquals(0.012, rules.multiplierFor("ME55", custom), 1e-9);
        assertEquals(1.0, rules.multiplierFor("ME55", mew), 1e-9);
    }

    @Test
    void foilHintForOpenedCards() {
        PackOpener o = opener(List.of(), List.of(), PackRules.none(), PackMerges.none(), 1);
        assertTrue(o.isFoil(Fixtures.card("SWSH1", 1, "X", Rarity.RARE_HOLO, 0, false)));
        assertFalse(o.isFoil(Fixtures.card("SWSH1", 2, "Y", Rarity.COMMON, 0, false)));
        assertEquals("RareHolo", Rarity.name(3));
        assertEquals("Common", Rarity.name(0));
        String names = String.join(",", Rarity.name(16), Rarity.name(99));
        assertEquals("RareUltra,UNSET", names);
    }
}
