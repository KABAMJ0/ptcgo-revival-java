package com.ptcgo.revival.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.PlayerRepository;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodeRepositoryTest {
    private CodeRepository codes;
    private PlayerRepository players;
    private String ash;
    private String misty;

    @BeforeEach
    void setUp() throws Exception {
        Database db = Fixtures.db();
        codes = new CodeRepository(db, new Random(1));
        players = new PlayerRepository(db);
        ash = Fixtures.account(db, "ash");
        misty = Fixtures.account(db, "misty");
        players.wallet(ash);
    }

    private static final String REWARD = "{\"products\":{\"pack-1\":2},\"coins\":50,\"gems\":3,\"tickets\":1}";

    @Test
    void redeemGrantsNonTradableProductsAndWallet() throws Exception {
        codes.create("hello", Fixtures.json(REWARD), 1, true).orElseThrow();
        var check = codes.check(" hello ", ash);
        assertTrue(check.ok());
        var r = codes.redeem("hello", ash);
        assertTrue(r.ok());
        assertEquals(new PlayerRepository.Wallet(1050, 3, 101), players.wallet(ash));
        var row = players.collection(ash).get(0);
        assertEquals("pack-1", row.archetypeId());
        assertEquals(0, row.tradable());
        assertEquals(2, row.nontradable());
        assertEquals(1, codes.get("HELLO").orElseThrow().currentUses());
    }

    @Test
    void failureReasonsMatchPython() throws Exception {
        assertEquals(CodeRepository.CODE_INVALID, codes.check("NOPE", ash).reason());
        codes.create("ONCE", Fixtures.json("{}"), 1, true);
        codes.create("OFF", Fixtures.json("{}"), 1, false);
        codes.create("MANY", Fixtures.json("{\"coins\":1}"), 0, true); // 0 = unlimited uses
        assertEquals(CodeRepository.CODE_INVALID, codes.check("OFF", ash).reason());
        assertTrue(codes.redeem("ONCE", ash).ok());
        // Python checks the use cap before the per-account entry, so a spent single-use code reads as expired
        assertEquals(CodeRepository.CODE_EXPIRED, codes.redeem("ONCE", ash).reason());
        assertEquals(CodeRepository.CODE_EXPIRED, codes.redeem("ONCE", misty).reason());
        assertTrue(codes.redeem("many", ash).ok());
        assertTrue(codes.redeem("MANY", misty).ok());
        assertEquals(CodeRepository.CODE_USED, codes.redeem("MANY", ash).reason());
        assertEquals(2, codes.get("MANY").orElseThrow().currentUses());
    }

    @Test
    void createGeneratesAndRejectsDuplicatesUpdateDelete() throws Exception {
        var made = codes.create("", Fixtures.json(REWARD), 5, true).orElseThrow();
        assertTrue(made.codeString().matches("SPIRIT(-[A-Z0-9]{4}){3}"), made.codeString());
        assertTrue(codes.create(made.codeString().toLowerCase(), Fixtures.json("{}"), 1, true).isEmpty());
        var upd = codes.update(made.codeString(), null, 9, false).orElseThrow();
        assertEquals(9, upd.maxUses());
        assertFalse(upd.enabled());
        assertEquals(REWARD.length() > 0, upd.reward().has("products"));
        assertEquals(1, codes.list().size());
        codes.create("X", Fixtures.json("{}"), 1, true);
        codes.redeem("X", ash);
        assertTrue(codes.delete("X")); // also removes its entries (foreign key)
        assertFalse(codes.delete("X"));
        assertTrue(codes.update("X", null, null, null).isEmpty());
    }

    @Test
    void redeemWithoutWalletRowStartsFromZero() throws Exception {
        codes.create("COINS", Fixtures.json("{\"coins\":7}"), 1, true);
        assertTrue(codes.redeem("COINS", misty).ok()); // misty has no wallet row yet
        assertEquals(7, players.wallet(misty).coins());
    }
}
