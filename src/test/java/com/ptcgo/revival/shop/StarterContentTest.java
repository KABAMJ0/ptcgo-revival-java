package com.ptcgo.revival.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.catalog.Catalog.Card;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.PlayerRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StarterContentTest {
    private static final String CONFIG = """
            {"starter_booster_pack_count":0,"tradable_packs_per_set":2,"tradable_pack_sets":["TST","MISSING","TSTG"],
             "set_code_map":{"LIVE":"TST"},
             "decks":[{"name":"Mine","cards":[[4,"LIVE",1],[3,"TST",7],[2,"NOPE",1]]}]}""";

    @Test
    void grantsOnceWalletDecksCosmeticsAndTradablePacks() throws Exception {
        Database db = Fixtures.db();
        List<Card> cards = new ArrayList<>(Fixtures.smallSet("TST"));
        cards.addAll(Fixtures.smallSet("TSTG"));
        Catalog catalog = Catalog.of(cards);
        ShopProduct tst = Fixtures.pack("11111111-1111-4111-8111-111111111111", "TST");
        ShopProduct tstg = Fixtures.pack("22222222-2222-4222-8222-222222222222", "TSTG");
        var merges = new PackMerges(Fixtures.json("{\"merge\":{\"TSTG\":{\"into\":\"TST\"}}}"));
        var starter = new StarterContent(db, catalog, new ShopProducts(List.of(tst, tstg)), merges, Fixtures.json(CONFIG));
        PlayerRepository players = new PlayerRepository(db);
        String acct = Fixtures.account(db, "ash");
        players.updateWallet(acct, 5, 5, 5);

        assertTrue(starter.grant(acct));
        assertFalse(starter.grant(acct), "second call is a no-op");

        assertEquals(new PlayerRepository.Wallet(1000, 0, 100), players.wallet(acct));
        var decks = players.decks(acct, false);
        assertEquals(1, decks.size());
        assertEquals("Mine", decks.get(0).name());
        assertEquals(7, decks.get(0).deckData().path("piles").path("deck").size()); // unknown card skipped
        assertEquals(3, decks.get(0).deckData().path("attributes").size());

        var owned = players.collection(acct);
        String c1 = catalog.guidsInSet("TST").iterator().next();
        assertEquals(4, owned.stream().filter(r -> r.archetypeId().equals(c1)).findFirst().orElseThrow().nontradable());
        for (String cosmetic : List.of(Cosmetics.DEFAULT_COIN, Cosmetics.DEFAULT_SLEEVE, Cosmetics.DEFAULT_DECK_BOX)) {
            assertEquals(1, owned.stream().filter(r -> r.archetypeId().equals(cosmetic)).findFirst().orElseThrow().nontradable());
        }
        // TST is eligible (>10 cards) and sold as a pack; TSTG is merged into TST so it is never handed out
        var pack = owned.stream().filter(r -> r.archetypeId().equals(tst.guid())).findFirst().orElseThrow();
        assertEquals(2, pack.tradable());
        assertTrue(owned.stream().noneMatch(r -> r.archetypeId().equals(tstg.guid())));
        assertEquals(1, players.decks(acct, false).size());
    }

    @Test
    void shippedConfigLoads() throws Exception {
        Database db = Fixtures.db();
        var starter = new StarterContent(db, Catalog.empty(), ShopProducts.empty(), PackMerges.defaults());
        String acct = Fixtures.account(db, "misty");
        assertTrue(starter.grant(acct)); // empty catalogue: decks resolve to nothing, cosmetics still granted
        assertEquals(4, new PlayerRepository(db).decks(acct, false).size());
        assertEquals(3, new PlayerRepository(db).collection(acct).size());
    }
}
