package com.ptcgo.revival.db;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.tournament.TournamentDef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TournamentRepositoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Database db;
    private TournamentRepository repo;
    private PlayerRepository players;
    private String acc;

    @BeforeEach
    void setUp() throws Exception {
        Path tmp = Files.createTempFile("ptcgo-tournament", ".db");
        tmp.toFile().deleteOnExit();
        db = new Database("jdbc:sqlite:" + tmp);
        db.migrate();
        repo = new TournamentRepository(db);
        players = new PlayerRepository(db);
        acc = new AccountRepository(db).create("ash", "pw", false).orElseThrow().accountId();
        players.updateWallet(acc, 1000, 5, 7);
    }

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    private JsonNode def() throws Exception {
        return json("""
                {"name":"Cup","maxRuns":1,"run":{"maxWins":2,"maxLosses":2,
                 "entryFee":[{"currency":"Tickets","amount":3},{"currency":"coins","amount":10}],
                 "prizeTable":[{"start":2,"end":2,"rewards":[
                    {"rewardType":"Tokens","rewardAmount":50,"rewardCurrency":"gems"},
                    {"rewardType":"Archetype","rewardAmount":2,"rewardProductID":"ABC-1"}]}]},
                 "leaderboard":{"winValue":0}}""");
    }

    @Test
    void createEntryChargesTheChosenCurrencyOnce() throws Exception {
        JsonNode d = def();
        repo.upsertTournament(d, "t1", true);
        TournamentRepository.EntryResult bad = repo.createEntry(acc, "t1", d, "gems", json("{}"));
        assertEquals("Invalid entry fee currency.", bad.error());
        assertEquals(7, players.wallet(acc).tickets());

        TournamentRepository.EntryResult ok = repo.createEntry(acc, "t1", d, "TICKETS", json("{\"deckID\":\"x\"}"));
        assertNull(ok.error());
        assertEquals(4, players.wallet(acc).tickets());
        assertEquals(1000, players.wallet(acc).coins());
        assertEquals("active", ok.entry().status());
        assertEquals("x", ok.entry().deck().get("deckID").asText());
        assertEquals(1, repo.countRuns(acc, "t1"));

        assertEquals("You already have an active run in this tournament.", repo.createEntry(acc, "t1", d, "coins", json("{}")).error());
        repo.finishEntry(ok.entry().entryId(), acc, d, true);
        assertEquals("No runs remaining for this tournament.", repo.createEntry(acc, "t1", d, "coins", json("{}")).error());

        // Not affordable: nothing is charged and no entry is created.
        ((ObjectNode) d).put("maxRuns", 0);
        players.updateWallet(acc, 1000, 5, 2);
        assertEquals("You cannot afford the entry fee.", repo.createEntry(acc, "t1", d, "tickets", json("{}")).error());
        assertEquals(2, players.wallet(acc).tickets());
        assertEquals(1, repo.countRuns(acc, "t1"));
    }

    @Test
    void gameResultsCapAndFinishGrantsOnce() throws Exception {
        JsonNode d = def();
        repo.upsertTournament(d, "t1", true);
        String id = repo.createEntry(acc, "t1", d, "coins", json("{}")).entry().entryId();
        TournamentRepository.GameRecorded g1 = repo.recordGameResult(id, true, "opp", "Opp", d.get("run"));
        assertFalse(g1.runComplete());
        TournamentRepository.GameRecorded g2 = repo.recordGameResult(id, true, "opp", "Opp", d.get("run"));
        assertTrue(g2.runComplete());
        assertEquals(2, g2.entry().wins());
        assertEquals("Win", g2.entry().history().get(1).get("gameResult").asText());

        TournamentRepository.Finished f = repo.finishEntry(id, acc, d, false);
        assertEquals(2, f.granted().size());
        assertEquals("complete", f.entry().status());
        assertEquals(5 + 50, players.wallet(acc).gems());
        assertEquals(2, players.collection(acc).stream().filter(c -> c.archetypeId().equals("abc-1")).findFirst().orElseThrow().nontradable());
        // Second finish (or a resign afterwards) grants nothing and keeps the status.
        assertTrue(repo.finishEntry(id, acc, d, true).granted().isEmpty());
        assertEquals("complete", repo.getEntry(id).status());
        assertEquals(55, players.wallet(acc).gems());
        assertNull(repo.finishEntry(id, "someone-else", d, false));
        assertNull(repo.recordGameResult(id, true, "o", "O", d.get("run"))); // no longer active
        assertNull(repo.finishEntry("missing", acc, d, false));
    }

    @Test
    void leaderboardUsesWinAndLossValuesWithPythonDefaults() throws Exception {
        JsonNode d = def(); // winValue 0 reads as the default 3 (Python `or 3`)
        repo.upsertTournament(d, "t1", true);
        String id = repo.createEntry(acc, "t1", d, "coins", json("{}")).entry().entryId();
        repo.recordGameResult(id, true, "o", "O", json("{}"));
        List<ObjectNode> st = repo.leaderboardStandings("t1", d);
        assertEquals(1, st.size());
        assertEquals(3.0, st.get(0).get("points").asDouble());
        assertEquals("ash", st.get(0).get("displayName").asText());
        assertTrue(repo.leaderboardStandings("none", d).isEmpty());
        TournamentRepository.Claim c = repo.claimLeaderboardReward(acc, "t1", json("{\"leaderboard\":{\"prizeTable\":[{\"start\":1,\"end\":1,\"rewards\":[{\"rewardType\":\"Tokens\",\"rewardAmount\":9}]}]}}"), st);
        assertEquals(1, c.rank());
        assertEquals(999, players.wallet(acc).coins()); // 1000 - 10 entry fee + 9 prize
        assertEquals("Leaderboard rewards already claimed.", repo.claimLeaderboardReward(acc, "t1", d, st).error());
        assertEquals(List.of("t1"), repo.claimedLeaderboards(acc));
    }

    @Test
    void feesChargeAllRowsAtomicallyAndRefund() throws Exception {
        List<TournamentRepository.Fee> fees = List.of(new TournamentRepository.Fee("coins", 100), new TournamentRepository.Fee("tickets", 3));
        assertNull(repo.chargeFees(acc, fees));
        assertEquals(900, players.wallet(acc).coins());
        assertEquals(4, players.wallet(acc).tickets());
        // Second row unaffordable: the first row must not be charged either.
        players.updateWallet(acc, 900, 5, 1);
        assertEquals("You cannot afford the entry fee.", repo.chargeFees(acc, fees));
        assertEquals(900, players.wallet(acc).coins());
        assertEquals("Invalid entry fee currency.", repo.chargeFees(acc, List.of(new TournamentRepository.Fee("bogus", 1))));
        repo.refundFees(acc, fees);
        assertEquals(1000, players.wallet(acc).coins());
        assertEquals(4, players.wallet(acc).tickets());
        repo.grantPrizeRewards(acc, List.of(json("{\"rewardType\":\"Tokens\",\"rewardAmount\":5,\"rewardCurrency\":\"event tickets\"}")));
        assertEquals(9, players.wallet(acc).tickets());
    }

    @Test
    void definitionsCrudAndPythonCreatedTablesAreUsable() throws Exception {
        repo.upsertTournament(json("{\"name\":\"A\"}"), "a", null);
        repo.upsertTournament(null, "a", false);
        TournamentRepository.Row row = repo.listTournaments(false).get(0);
        assertEquals("A", row.definition().get("name").asText());
        assertFalse(row.enabled());
        assertTrue(repo.listTournaments(true).isEmpty());
        assertTrue(repo.deleteTournament("a"));
        assertFalse(repo.deleteTournament("a"));

        // A database written by the Python server (SQLAlchemy DDL, json.dumps text) is read as is and migrate() leaves it alone.
        Path tmp = Files.createTempFile("ptcgo-pytournament", ".db");
        tmp.toFile().deleteOnExit();
        Database py = new Database("jdbc:sqlite:" + tmp);
        try (Connection c = py.connect(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE async_tournaments (tournament_id VARCHAR NOT NULL, definition_json TEXT NOT NULL, "
                    + "enabled BOOLEAN NOT NULL, created_at DATETIME DEFAULT (CURRENT_TIMESTAMP) NOT NULL, PRIMARY KEY (tournament_id))");
            st.execute("CREATE TABLE tournament_entries (entry_id VARCHAR NOT NULL, tournament_id VARCHAR NOT NULL, account_id VARCHAR NOT NULL, "
                    + "deck_json TEXT, wins INTEGER NOT NULL, losses INTEGER NOT NULL, tiebreakers INTEGER NOT NULL, status VARCHAR NOT NULL, "
                    + "rewards_claimed BOOLEAN NOT NULL, history_json TEXT, last_update BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT (CURRENT_TIMESTAMP) NOT NULL, PRIMARY KEY (entry_id), "
                    + "FOREIGN KEY(tournament_id) REFERENCES async_tournaments (tournament_id))");
            st.execute("INSERT INTO async_tournaments (tournament_id, definition_json, enabled) VALUES ('p1', '{\"name\": \"Py\", \"run\": {\"maxWins\": 1}}', 1)");
            st.execute("INSERT INTO tournament_entries (entry_id, tournament_id, account_id, deck_json, wins, losses, tiebreakers, status, rewards_claimed, history_json, last_update) "
                    + "VALUES ('e1', 'p1', 'acc', NULL, 1, 0, 0, 'active', 0, NULL, 5)");
        }
        py.migrate();
        TournamentRepository pyRepo = new TournamentRepository(py);
        assertEquals("Py", pyRepo.listTournaments(false).get(0).definition().get("name").asText());
        TournamentRepository.Entry e = pyRepo.getEntry("e1");
        assertTrue(e.deck().isObject());
        assertTrue(e.history().isArray());
        assertEquals(1, pyRepo.activeEntries("acc").size());
    }

    @Test
    void definitionValidationAndStates() throws Exception {
        assertNull(TournamentDef.validateDefinition(json("{\"name\":\"x\",\"maxSize\":4,\"run\":{\"maxWins\":1}}")));
        assertEquals("name required", TournamentDef.validateDefinition(json("{}")));
        assertEquals("maxSize must be 2, 4 or 8 (the bracket UI renders 8-player brackets)",
                TournamentDef.validateDefinition(json("{\"name\":\"x\",\"maxSize\":3,\"run\":{\"maxWins\":1}}")));
        assertEquals("run needs at least one of maxWins/maxLosses/maxGames > 0", TournamentDef.validateDefinition(json("{\"name\":\"x\"}")));
        assertEquals("times must be in order: preview <= start <= entryClosing <= resolution <= disappear",
                TournamentDef.validateDefinition(json("{\"name\":\"x\",\"previewTime\":5,\"startTime\":1,\"run\":{\"maxWins\":1}}")));
        assertEquals("unknown entry fee currency: dollars", TournamentDef.validateDefinition(
                json("{\"name\":\"x\",\"run\":{\"maxWins\":1,\"entryFee\":[{\"currency\":\"dollars\",\"amount\":1}]}}")));
        assertEquals("Archetype reward missing rewardProductID", TournamentDef.validateDefinition(
                json("{\"name\":\"x\",\"run\":{\"maxWins\":1,\"prizeTable\":[{\"start\":1,\"end\":1,\"rewards\":[{\"rewardType\":\"Archetype\"}]}]}}")));
        assertEquals("format must be one of Unlimited, ThemeDeck, Modified, Expanded, Legacy", TournamentDef.validateDefinition(
                json("{\"name\":\"x\",\"format\":\"Weird\",\"run\":{\"maxWins\":1}}")));

        TournamentDef t = new TournamentDef("t", json("{\"previewTime\":10,\"startTime\":20,\"entryClosingTime\":30,\"resolutionTime\":40,\"disappearTime\":50}"), true);
        assertEquals("BeforePreview", t.state(5));
        assertEquals("Preview", t.state(15));
        assertEquals("Open", t.state(25));
        assertEquals("EntryClosed", t.state(35));
        assertEquals("Resolved", t.state(45));
        assertEquals("Hidden", t.state(55));
        // Unset closing times default to the far future.
        assertEquals("Open", new TournamentDef("t", json("{\"startTime\":1}"), true).state(1_000_000));
    }
}
