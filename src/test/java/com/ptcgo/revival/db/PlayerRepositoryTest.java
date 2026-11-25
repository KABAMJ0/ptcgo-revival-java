package com.ptcgo.revival.db;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerRepositoryTest {
    private PlayerRepository repo;
    private String acct;

    @BeforeEach
    void setUp() throws Exception {
        var tmp = Files.createTempFile("ptcgo-repo", ".db");
        tmp.toFile().deleteOnExit();
        Database db = new Database("jdbc:sqlite:" + tmp);
        db.migrate();
        acct = new AccountRepository(db).create("ash", "pw", false).orElseThrow().accountId();
        repo = new PlayerRepository(db);
    }

    @Test
    void walletDefaultsAndClamp() throws Exception {
        assertEquals(new PlayerRepository.Wallet(1000, 0, 100), repo.wallet(acct));
        repo.updateWallet(acct, 5_000_000_000L, -5, 7);
        assertEquals(new PlayerRepository.Wallet(2_000_000_000, 0, 7), repo.wallet(acct));
    }

    @Test
    void collectionAddRemoveFloorsAtZero() throws Exception {
        repo.addToCollection(acct, Map.of("g1", 2), true);
        repo.addToCollection(acct, Map.of("g1", 3), false);
        repo.removeFromCollection(acct, "g1", 10, true);
        var row = repo.collection(acct).get(0);
        assertEquals(0, row.tradable());
        assertEquals(3, row.nontradable());
        // Removing an unknown item creates a zero row (so a virtual starter copy can't reappear).
        repo.removeFromCollection(acct, "ghost", 1, false);
        assertEquals(2, repo.collection(acct).size());
        assertEquals(Map.of("g1", 3, "ghost", 0), repo.ownedCounts(acct));
    }

    @Test
    void flagsDeletedWhenAllZero() throws Exception {
        repo.setFlag(acct, "ABC", 1, null, null);
        repo.setFlag(acct, "abc", null, 1, null);
        var f = repo.flags(acct).get(0);
        assertEquals("abc", f.archetypeId());
        assertEquals(1, f.wanted());
        assertEquals(1, f.forTrade());
        repo.setFlag(acct, "abc", 0, 0, 0);
        assertTrue(repo.flags(acct).isEmpty());
    }

    @Test
    void decksSaveUpdateDelete() throws Exception {
        var json = new ObjectMapper();
        repo.saveDeck(acct, "d1", "One", json.readTree("{\"deckID\":\"d1\"}"), false);
        repo.saveDeck(acct, "d1", "Renamed", json.readTree("{\"deckID\":\"d1\",\"x\":1}"), false);
        repo.saveDeck(acct, "a1", "Avatar", json.readTree("{\"deckID\":\"a1\"}"), true);
        assertEquals(1, repo.decks(acct, false).size());
        assertEquals("Renamed", repo.decks(acct, false).get(0).name());
        assertEquals(1, repo.decks(acct, false).get(0).deckData().get("x").asInt());
        assertTrue(repo.isAvatarDeck(acct, "a1"));
        assertFalse(repo.deleteDeck("someone-else", "d1"));
        assertTrue(repo.deleteDeck(acct, "d1"));
        assertTrue(repo.decks(acct, false).isEmpty());
    }

    @Test
    void settingsMerge() throws Exception {
        repo.mergeAccountSettings(acct, Map.of("1", 5, "2", 6));
        var merged = repo.mergeAccountSettings(acct, Map.of("2", 9));
        assertEquals(Map.of("1", 5, "2", 9), merged);
    }
}
