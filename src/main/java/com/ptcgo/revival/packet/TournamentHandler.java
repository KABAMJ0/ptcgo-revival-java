package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.db.TournamentRepository;
import com.ptcgo.revival.tournament.AsyncTournamentMatchmaker;
import com.ptcgo.revival.tournament.LiveTournament;
import com.ptcgo.revival.tournament.LiveTournamentManager;
import com.ptcgo.revival.tournament.TournamentDeckChecker;
import com.ptcgo.revival.tournament.TournamentDef;
import com.ptcgo.revival.tournament.TournamentManager;
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
 * Every tournament message the Python server serves (ptcgo/packets/handlers/tournaments.py): the async (run +
 * leaderboard) tournaments and the legacy Events-scene live queues. Reply shapes and error strings match Python.
 */
public final class TournamentHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(TournamentHandler.class);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final String ZERO_GUID = "00000000-0000-0000-0000-000000000000";
    private static final byte[] EMPTY_BODY = {'{', '}'};

    private final TournamentRepository repo;
    private final TournamentManager tournaments;
    private final LiveTournamentManager live;
    private final AsyncTournamentMatchmaker matchmaker;
    private final TournamentDeckChecker checker;

    public TournamentHandler(TournamentRepository repo, TournamentManager tournaments, LiveTournamentManager live,
                             AsyncTournamentMatchmaker matchmaker, TournamentDeckChecker checker) {
        this.repo = repo;
        this.tournaments = tournaments;
        this.live = live;
        this.matchmaker = matchmaker;
        this.checker = checker;
    }

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("GetActiveAsyncTournaments", this::getActiveAsyncTournaments);
        m.put("GetActiveTournaments", this::getActiveTournamentsLegacy);
        m.put("SubscribeToTournamentsChannel", this::subscribe);
        m.put("UnSubscribeToTournamentsChannel", this::unsubscribe);
        m.put("JoinTournament", this::joinTournament);
        m.put("LeaveTournamentQueue", this::leaveTournamentQueue);
        m.put("GetPlayersInQueue", this::getPlayersInQueue);
        m.put("GetTournamentInProgress", this::getTournamentInProgress);
        m.put("LeaveActiveTournament", this::leaveActiveTournament);
        m.put("IsUserInActiveTournament", this::isUserInActiveTournament);
        m.put("GetActiveTournament", this::isUserInActiveTournament);
        m.put("GetNumberOfPlayerRuns", this::getNumberOfPlayerRuns);
        m.put("JoinAsyncTournament", this::joinAsyncTournament);
        m.put("StartAsyncTournamentGame", this::startAsyncTournamentGame);
        m.put("ResignTournamentRun", this::resignTournamentRun);
        m.put("ClaimAsyncTournamentReward", this::claimAsyncTournamentReward);
        m.put("GetAsyncTournamentGameHistory", this::getGameHistory);
        m.put("GetAsyncTournamentStandings", this::getStandings);
        m.put("GetTopRankedStandings", this::getTopRankedStandings);
        m.put("GetPlayerRankAndSurroundingStandings", this::getPlayerRankAndSurrounding);
        m.put("GetCanClaimLeaderboardRewardsForTournament", this::getCanClaimLeaderboardRewards);
        m.put("HasClaimedAsyncTournamentLeaderboardReward", this::hasClaimedLeaderboardReward);
        m.put("ClaimAsyncTournamentLeaderboardReward", this::claimLeaderboardReward);
        m.put("GetUnacknowledgedPacks", this::getUnacknowledgedPacks);
        m.put("AcknowledgePack", this::acknowledgePack);
        m.put("GetLeagueTiebreakersRemaining", this::getLeagueTiebreakersRemaining);
        return m;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String text(JsonNode v, String field) {
        return v != null && v.hasNonNull(field) ? v.get(field).asText() : "";
    }

    private static int intOf(JsonNode v, String field, int dflt) {
        if (v == null || !v.hasNonNull(field)) return dflt;
        JsonNode n = v.get(field);
        int x = n.isNumber() ? n.asInt() : (int) parse(n.asText(), 0);
        return x == 0 ? dflt : x;
    }

    private static long parse(String s, long dflt) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String account(ClientContext c) {
        return c.account().accountId();
    }

    private static void error(ClientContext c, String messageName, String text, int rid) {
        c.send(messageName, Map.of("error", Map.of("id", text)), rid);
    }

    /** Snapshot of the player's stored deck (by GUID) as a SerializableDeck; null when not found. */
    private ObjectNode resolveDeckJson(ClientContext c, String deckId) throws SQLException {
        if (deckId == null || deckId.isEmpty()) return null;
        for (PlayerRepository.DeckRow d : c.services().players().decks(account(c), false)) {
            if (d.id().equalsIgnoreCase(deckId) && d.deckData() != null && d.deckData().size() > 0) {
                return serializableDeck(d.deckData(), d.name() == null || d.name().isEmpty() ? "Tournament Deck" : d.name());
            }
        }
        return null;
    }

    /** Normalises a stored deck_data into the client SerializableDeck shape. */
    static ObjectNode serializableDeck(JsonNode deckData, String name) {
        if (deckData.has("piles")) {
            ObjectNode out = deckData.deepCopy();
            if (!out.has("deckID")) out.put("deckID", UUID.randomUUID().toString());
            if (!out.has("deckName")) out.put("deckName", name);
            if (!out.has("attributes")) out.putArray("attributes");
            return out;
        }
        ArrayNode guids = F.arrayNode();
        for (JsonNode card : deckData.path("cards")) {
            int count = card.path("count").asInt(0);
            for (int i = 0; i < count; i++) guids.add(card.get("guid"));
        }
        ObjectNode out = F.objectNode();
        out.put("deckID", deckData.hasNonNull("deckID") && !deckData.get("deckID").asText().isEmpty()
                ? deckData.get("deckID").asText() : UUID.randomUUID().toString());
        out.put("deckName", deckData.hasNonNull("deckName") && !deckData.get("deckName").asText().isEmpty()
                ? deckData.get("deckName").asText() : name);
        out.putObject("piles").set("deck", guids);
        JsonNode attrs = deckData.get("attributes");
        out.set("attributes", attrs != null && attrs.isArray() && attrs.size() > 0 ? attrs : F.arrayNode());
        return out;
    }

    /** The configured format for one flow: legacy uses the top-level format, async prefers game.format. */
    private static String formatValue(TournamentDef t, boolean legacy) {
        JsonNode d = t.definition();
        String top = d.hasNonNull("format") && !d.get("format").asText().isEmpty() ? d.get("format").asText() : "Unlimited";
        if (legacy) return top;
        JsonNode game = d.path("game").path("format");
        return game.isTextual() && !game.asText().isEmpty() ? game.asText() : top;
    }

    private TournamentDeckChecker.Result validate(ClientContext c, JsonNode deck, TournamentDef t, boolean legacy)
            throws SQLException {
        Map<String, Integer> owned = c.services().players().ownedCounts(account(c));
        return checker.check(deck, owned, formatValue(t, legacy));
    }

    private static boolean valid(TournamentDeckChecker.Result r) {
        return !r.rows().isEmpty() && r.rows().get(0).valid();
    }

    static ObjectNode progress(TournamentRepository.Entry e) {
        ObjectNode n = F.objectNode();
        n.put("tournamentDefID", e.tournamentId());
        n.put("entryID", e.entryId());
        n.set("deck", e.deck() == null ? F.objectNode() : e.deck());
        n.put("wins", e.wins());
        n.put("losses", e.losses());
        n.put("lastUpdate", e.lastUpdate());
        n.putArray("limitedCollection");
        n.put("tiebreakers", e.tiebreakers());
        return n;
    }

    private static boolean anyArchetype(List<JsonNode> granted) {
        return granted.stream().anyMatch(r -> "Archetype".equals(r.path("rewardType").asText(null)));
    }

    private static void pushWallet(ClientContext c) throws SQLException {
        WalletSync.pushWallet(c);
    }

    private static boolean ended(TournamentDef t) {
        String s = t.state(System.currentTimeMillis());
        return TournamentDef.STATE_RESOLVED.equals(s) || TournamentDef.STATE_HIDDEN.equals(s);
    }

    // ---- listing ---------------------------------------------------------------------------------

    private void getActiveAsyncTournaments(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        List<TournamentDef> visible = tournaments.visible();
        List<String> ids = visible.stream().map(t -> t.tournamentId().toLowerCase(Locale.ROOT)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentDefinitions", visible.stream().map(TournamentDef::toClientDict).toList());
        out.put("tournamentProgress", repo.activeEntries(account(c)).stream()
                .filter(e -> ids.contains(e.tournamentId().toLowerCase(Locale.ROOT))).map(TournamentHandler::progress).toList());
        Map<String, Boolean> claimed = new LinkedHashMap<>();
        for (String id : repo.claimedLeaderboards(account(c))) claimed.put(id, true);
        out.put("claimedLeaderboard", claimed);
        c.send("ActiveAsyncTournaments", out, rid);
    }

    // ---- legacy Events scene ---------------------------------------------------------------------

    private static void joinFailed(ClientContext c, String text, int rid) {
        c.send("JoinTournamentFailed", Map.of("reason", Map.of("id", text)), rid);
    }

    private void getActiveTournamentsLegacy(ClientContext c, JsonNode v, int rid, int f) {
        // Zero entries safely renders the maintenance / "no events" panel.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentList", tournaments.visible().stream().map(TournamentDef::toLegacyDict).toList());
        out.put("tournamentQueues", live.queueCounts());
        c.send("AvailableTournamentList", out, rid);
    }

    private void subscribe(ClientContext c, JsonNode v, int rid, int f) {
        live.subscribe(c);
        c.send("SubscribeToTournamentChannelSuccessful", Map.of("tournamentQueues", live.queueCounts()), rid);
    }

    private void unsubscribe(ClientContext c, JsonNode v, int rid, int f) {
        live.unsubscribe(c);
    }

    private void joinTournament(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        synchronized (live) { // check, charge and enqueue must not interleave with a second join of the same account
            String tournamentId = text(v, "tournamentID");
            TournamentDef t = tournaments.get(tournamentId);
            String accountId = account(c);

            if (t == null || !t.enabled() || !TournamentDef.STATE_OPEN.equals(t.state(System.currentTimeMillis()))) {
                joinFailed(c, "That tournament is not open for registration.", rid);
                return;
            }
            if (live.findQueued(accountId) != null || live.findActiveFor(accountId) != null) {
                joinFailed(c, "You are already in a tournament.", rid);
                return;
            }
            ObjectNode deck = resolveDeckJson(c, text(v, "deck"));
            if (deck == null) {
                joinFailed(c, "The selected deck could not be found.", rid);
                return;
            }
            TournamentDeckChecker.Result result = validate(c, deck, t, true);
            if (result.formatError() != null) {
                joinFailed(c, result.formatError(), rid);
                return;
            }
            if (!valid(result)) {
                ArrayNode rows = F.arrayNode();
                result.rows().forEach(r -> rows.add((JsonNode) r.toJson()));
                c.send("JoinTournamentFailedInvalidDeck", Map.of("deckValidationResult", rows), rid);
                return;
            }
            String err = repo.chargeFees(accountId, t.legacyEntryFees());
            if (err != null) {
                // WalletFailed makes the client undo its optimistic local deduction; follow with a wallet push so the
                // HUD resyncs regardless.
                joinFailed(c, "event.join.error.WalletFailed", rid);
                pushWallet(c);
                return;
            }
            LOG.info("[Tournaments] {} queued for '{}'", c.account().username(), t.definition().path("name").asText());
            c.send("TournamentQueueJoined", Map.of("tournamentID", t.tournamentId()), rid);
            pushWallet(c);
            live.joinQueue(c, t, deck);
        }
    }

    private void leaveTournamentQueue(ClientContext c, JsonNode v, int rid, int f) {
        if (c.account() == null) return;
        String tournamentId = text(v, "tournamentID");
        boolean left = live.leaveQueue(account(c), tournamentId, c);
        c.send(left ? "TournamentQueueLeft" : "TournamentQueueLeftFailed", Map.of("tournamentID", tournamentId), rid);
    }

    private void getPlayersInQueue(ClientContext c, JsonNode v, int rid, int f) {
        c.send("UsersInTournamentQueue", Map.of("usernames", live.usernamesInQueue(text(v, "tournamentID"))), rid);
    }

    private void getTournamentInProgress(ClientContext c, JsonNode v, int rid, int f) {
        if (c.account() == null) return;
        String activeId = text(v, "activeTournamentID").toLowerCase(Locale.ROOT);
        synchronized (live) {
            LiveTournament t = live.activeById(activeId);
            if (t == null) t = live.findActiveFor(account(c));
            List<Map<String, Object>> data = new ArrayList<>();
            if (t != null) data.add(t.progress());
            c.send("TournamentsInProgressData", Map.of("tournamentData", data), rid);
        }
    }

    private void leaveActiveTournament(ClientContext c, JsonNode v, int rid, int f) {
        if (c.account() == null) return;
        String activeId = text(v, "activeTournamentID").toLowerCase(Locale.ROOT);
        live.withdraw(activeId, account(c));
        c.send("TournamentLeft", Map.of("activeTournamentID", activeId), rid);
    }

    private void isUserInActiveTournament(ClientContext c, JsonNode v, int rid, int f) {
        if (c.account() == null) return;
        LiveTournament t = live.findActiveFor(account(c));
        c.send("UserInActiveTournament", Map.of("activeTournamentID", t == null ? ZERO_GUID : t.activeId()), rid);
    }

    private void getNumberOfPlayerRuns(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        String tournamentId = text(v, "tournamentID");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentID", tournamentId);
        out.put("runs", repo.countRuns(account(c), tournamentId));
        c.send("AsyncTournamentNumberOfPlayerRuns", out, rid);
    }

    // ---- join / play -----------------------------------------------------------------------------

    private void joinAsyncTournament(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        final String name = "JoinAsyncTournamentError";
        String tournamentId = text(v, "tournamentID");
        String currency = text(v, "currency");
        TournamentDef t = tournaments.get(tournamentId);
        if (t == null || !t.enabled()) {
            error(c, name, "This tournament is not available.", rid);
            return;
        }
        if (!TournamentDef.STATE_OPEN.equals(t.state(System.currentTimeMillis()))) {
            error(c, name, "This tournament is not open for entries.", rid);
            return;
        }
        ObjectNode deck = resolveDeckJson(c, text(v, "deckID"));
        if (deck == null) {
            error(c, name, "The selected deck could not be found.", rid);
            return;
        }
        TournamentDeckChecker.Result result = validate(c, deck, t, false);
        if (result.formatError() != null) {
            error(c, name, result.formatError(), rid);
            return;
        }
        if (!valid(result)) {
            error(c, name, TournamentDeckChecker.errorText(result.rows()), rid);
            return;
        }
        TournamentRepository.EntryResult created = repo.createEntry(account(c), t.tournamentId(), t.definition(), currency, deck);
        if (created.error() != null) {
            error(c, name, created.error(), rid);
            return;
        }
        TournamentRepository.Entry entry = created.entry();
        LOG.info("[Tournaments] {} joined '{}' (entry {})", c.account().username(),
                t.definition().path("name").asText(), entry.entryId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentID", t.tournamentId());
        out.put("entryID", entry.entryId());
        out.put("deck", entry.deck());
        out.put("progress", progress(entry));
        c.send("AsyncTournamentJoined", out, rid);
        pushWallet(c);
    }

    private void startAsyncTournamentGame(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        final String name = "StartAsyncTournamentGameError";
        String entryId = text(v, "entryID");
        String deckId = text(v, "deckID");
        TournamentRepository.Entry entry = repo.getEntry(entryId);
        if (entry == null || !entry.accountId().equals(account(c))) {
            error(c, name, "Tournament run not found.", rid);
            return;
        }
        if (!"active".equals(entry.status())) {
            error(c, name, "This tournament run is already complete.", rid);
            return;
        }
        TournamentDef t = tournaments.get(entry.tournamentId());
        if (t == null || !t.enabled()) {
            error(c, name, "This tournament is no longer available.", rid);
            return;
        }
        if (ended(t)) {
            error(c, name, "This tournament has ended.", rid);
            return;
        }
        JsonNode runCfg = t.runConfig();
        JsonNode switching = runCfg.get("allowDeckSwitching");
        boolean allowSwitch = switching == null || switching.isMissingNode() || switching.isNull() ? true : truthy(switching);
        JsonNode deck = entry.deck();
        if (!deckId.isEmpty() && allowSwitch) {
            ObjectNode fresh = resolveDeckJson(c, deckId);
            if (fresh == null) {
                error(c, name, "The selected deck could not be found.", rid);
                return;
            }
            TournamentDeckChecker.Result r = validate(c, fresh, t, false);
            if (r.formatError() != null) {
                error(c, name, r.formatError(), rid);
                return;
            }
            if (!valid(r)) {
                error(c, name, TournamentDeckChecker.errorText(r.rows()), rid);
                return;
            }
            repo.updateEntryDeck(entryId, account(c), fresh);
            deck = fresh;
            Map<String, Object> upd = new LinkedHashMap<>();
            upd.put("tournamentID", entry.tournamentId());
            upd.put("entryID", entryId);
            upd.put("deck", fresh);
            upd.put("limitedCollection", List.of());
            c.send("AsyncTournamentDeckUpdated", upd, 0);
        }
        TournamentDeckChecker.Result r = validate(c, deck, t, false);
        if (r.formatError() != null) {
            error(c, name, r.formatError(), rid);
            return;
        }
        if (!valid(r)) {
            error(c, name, TournamentDeckChecker.errorText(r.rows()), rid);
            return;
        }
        if (!matchmaker.isAvailable()) {
            error(c, name, "Tournament matches are not available on this server yet.", rid);
            return;
        }
        // Complete the RPC, then ride the normal matchmaking pipeline (MatchQueueEntered, ConfirmReadyForMatch, MatchFound).
        c.sendRaw(EMPTY_BODY, rid, 0);
        matchmaker.enqueue(c, "AsyncTournament_" + t.tournamentId(), deck, t.tournamentId(), entryId);
    }

    private static boolean truthy(JsonNode n) {
        if (n.isBoolean()) return n.asBoolean();
        if (n.isNumber()) return n.asDouble() != 0;
        if (n.isTextual()) return !n.asText().isEmpty();
        return n.size() > 0;
    }

    private void sendRewards(ClientContext c, TournamentRepository.Finished fin, String entryId, int rid) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entryID", entryId);
        out.put("wins", fin.entry().wins());
        out.put("losses", fin.entry().losses());
        out.put("rewards", TournamentDef.clientRewards(fin.granted()));
        c.send("AsyncTournamentRewards", out, rid);
        pushWallet(c);
        if (anyArchetype(fin.granted())) WalletSync.pushCollection(c);
    }

    private void resignTournamentRun(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        String entryId = text(v, "entryID");
        TournamentRepository.Entry entry = repo.getEntry(entryId);
        if (entry == null || !entry.accountId().equals(account(c))) return;
        TournamentDef t = tournaments.get(entry.tournamentId());
        TournamentRepository.Finished fin = repo.finishEntry(entryId, account(c), t == null ? null : t.definition(), true);
        if (fin != null) sendRewards(c, fin, entryId, rid);
    }

    private void claimAsyncTournamentReward(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        final String name = "ClaimAsyncTournamentRewardError";
        String entryId = text(v, "entryID");
        TournamentRepository.Entry entry = repo.getEntry(entryId);
        if (entry == null || !entry.accountId().equals(account(c))) {
            error(c, name, "Tournament run not found.", rid);
            return;
        }
        TournamentDef t = tournaments.get(entry.tournamentId());
        TournamentRepository.Finished fin = repo.finishEntry(entryId, account(c), t == null ? null : t.definition(), false);
        if (fin == null) {
            error(c, name, "Unable to claim rewards for this run.", rid);
            return;
        }
        sendRewards(c, fin, entryId, rid);
    }

    // ---- history ---------------------------------------------------------------------------------

    private void getGameHistory(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        String entryId = text(v, "entryID");
        TournamentRepository.Entry entry = repo.getEntry(entryId);
        Object games = entry != null && entry.accountId().equals(account(c)) ? entry.history() : F.arrayNode();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentID", text(v, "tournamentID"));
        out.put("entryID", entryId);
        out.put("games", games);
        c.send("AsyncTournamentGameHistoryList", out, rid);
    }

    // ---- standings -------------------------------------------------------------------------------

    private List<ObjectNode> standings(String tournamentId) throws SQLException {
        TournamentDef t = tournaments.get(tournamentId);
        return repo.leaderboardStandings(tournamentId, t == null ? null : t.definition());
    }

    private static ObjectNode mine(List<ObjectNode> standings, String accountId) {
        return standings.stream().filter(s -> s.path("accountID").asText().equals(accountId)).findFirst().orElse(null);
    }

    private void getStandings(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        c.send("AsyncTournamentStandings", Map.of("standings", standings(text(v, "tournamentID"))), rid);
    }

    private void getTopRankedStandings(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        List<ObjectNode> standings = standings(text(v, "tournamentID"));
        int count = Math.max(1, intOf(v, "count", 10));
        ObjectNode me = mine(standings, account(c));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("standings", standings.subList(0, Math.min(count, standings.size())));
        out.put("playerRank", me == null ? 0 : me.path("rank").asInt());
        out.put("playerStanding", me);
        c.send("AsyncTournamentLeaderboardTopRankedStandings", out, rid);
    }

    private void getPlayerRankAndSurrounding(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        List<ObjectNode> standings = standings(text(v, "tournamentID"));
        int above = Math.max(0, intOf(v, "above", 0));
        int below = Math.max(0, intOf(v, "below", 0));
        ObjectNode me = mine(standings, account(c));
        Map<Integer, ObjectNode> map = new LinkedHashMap<>();
        if (me != null) {
            int rank = me.path("rank").asInt();
            int lo = Math.max(0, rank - 1 - above);
            int hi = Math.min(standings.size(), rank + below);
            for (ObjectNode s : standings.subList(lo, Math.max(lo, hi))) map.put(s.path("rank").asInt(), s);
        }
        c.send("AsyncTournamentLeaderboardPlayerRankAndSurroundingStandings", Map.of("standingMap", map), rid);
    }

    // ---- leaderboard claims ----------------------------------------------------------------------

    private void getCanClaimLeaderboardRewards(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        String tournamentId = text(v, "tournamentID");
        TournamentDef t = tournaments.get(tournamentId);
        int runs = repo.countRuns(account(c), tournamentId);
        boolean claimed = repo.hasClaimedLeaderboard(account(c), tournamentId);
        boolean resolved = t != null && ended(t);
        Integer rank = null;
        if (runs > 0 && resolved) {
            ObjectNode me = mine(standings(tournamentId), account(c));
            rank = me == null ? null : me.path("rank").asInt();
        }
        long availableIn = 0;
        if (t != null && !resolved) availableIn = Math.max(0, t.resolutionTime() - System.currentTimeMillis());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentID", tournamentId);
        out.put("hasParticipated", runs > 0);
        out.put("claimAvailable", runs > 0 && resolved && !claimed);
        out.put("rank", rank);
        out.put("availableIn", availableIn);
        c.send("CanClaimLeaderboardRewardsForTournament", out, rid);
    }

    private void hasClaimedLeaderboardReward(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        String tournamentId = text(v, "tournamentID");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentID", tournamentId);
        out.put("claimed", repo.hasClaimedLeaderboard(account(c), tournamentId));
        c.send("AsyncTournamentHasClaimedLeaderboardReward", out, rid);
    }

    private void claimLeaderboardReward(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        final String name = "ClaimAsyncTournamentRewardError";
        String tournamentId = text(v, "tournamentID");
        TournamentDef t = tournaments.get(tournamentId);
        if (t == null || !ended(t)) {
            error(c, name, "Leaderboard rewards are not available yet.", rid);
            return;
        }
        TournamentRepository.Claim claim = repo.claimLeaderboardReward(account(c), tournamentId, t.definition(), standings(tournamentId));
        if (claim.rank() == null) {
            error(c, name, claim.error(), rid);
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentID", tournamentId);
        out.put("rank", claim.rank());
        out.put("rewards", TournamentDef.clientRewards(claim.granted()));
        c.send("AsyncTournamentLeaderboardRewards", out, rid);
        pushWallet(c);
        if (anyArchetype(claim.granted())) WalletSync.pushCollection(c);
    }

    // ---- packs / league stubs --------------------------------------------------------------------

    private void getUnacknowledgedPacks(ClientContext c, JsonNode v, int rid, int f) {
        // Product rewards are granted straight to the collection; no pack-opening queue.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentEntryID", text(v, "entryID"));
        out.put("packs", List.of());
        out.put("additionalRewards", null);
        c.send("AsyncTournamentUnacknowledgedPacks", out, rid);
    }

    private void acknowledgePack(ClientContext c, JsonNode v, int rid, int f) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentEntryID", text(v, "entryID"));
        out.put("packID", text(v, "packID"));
        c.send("PackAcknowledged", out, rid);
    }

    private void getLeagueTiebreakersRemaining(ClientContext c, JsonNode v, int rid, int f) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tournamentID", text(v, "tournamentID"));
        out.put("entryID", text(v, "entryID"));
        out.put("week", 0);
        out.put("tiebreakersPlayedThisWeek", 0);
        out.put("tiebreakersRemaining", 0);
        c.send("LeagueTiebreakersRemaining", out, rid);
    }
}
