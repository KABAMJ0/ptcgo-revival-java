package com.ptcgo.revival.game.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.deck.FormatManager;
import com.ptcgo.revival.deck.RulesDeckValidator;
import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.engine.AiPlayer;
import com.ptcgo.revival.game.engine.GamePhase;
import com.ptcgo.revival.game.engine.GamePlayer;
import com.ptcgo.revival.game.engine.GameSession;
import com.ptcgo.revival.game.engine.MatchHooks;
import com.ptcgo.revival.game.engine.MatchSettings;
import com.ptcgo.revival.game.engine.NetworkPlayer;
import com.ptcgo.revival.packet.ClientContext;
import com.ptcgo.revival.tournament.TournamentMatchRunner;
import com.ptcgo.revival.social.FriendMatchStarter;
import com.ptcgo.revival.social.GameChatRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import static com.ptcgo.revival.game.wire.Msgs.obj;

/**
 * Matchmaking and the registry of running matches (ptcgo/game/session/manager.py): format queues with the bot
 * fallback, the ready check, solo matches against the AI, friend challenges (as {@link FriendMatchStarter}) and the
 * in-game chat route (as {@link GameChatRouter}). One instance per server; every state change is synchronized.
 */
public final class MatchManager implements FriendMatchStarter, GameChatRouter {
    private static final Logger LOG = LoggerFactory.getLogger(MatchManager.class);

    /** The client parses the MatchFound player list as GUIDs, so the bot needs a GUID-shaped account id. */
    public static final String BOT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000b07";
    /** A client-supplied queueName longer than this is rejected (the queues map is keyed by it). */
    static final int MAX_QUEUE_NAME_LEN = 64;

    record QueueEntry(ClientContext client, JsonNode deck, JsonNode options) {}

    static final class PairingPlayer {
        final ClientContext client; // null for the bot
        final JsonNode deck;
        boolean ready;

        PairingPlayer(ClientContext client, JsonNode deck, boolean ready) {
            this.client = client;
            this.deck = deck;
            this.ready = ready;
        }
    }

    static final class Pairing {
        final String gameId;
        final Map<String, PairingPlayer> players = new LinkedHashMap<>();
        boolean solo;
        String solitaireId;
        JsonNode options;
        String queueName;
        /** Live-bracket matches only: tournament id (gameOptions["TournamentID"]) and the result listener. */
        String tournamentId;
        GameSession.GameResultListener tournamentListener;

        Pairing(String gameId) {
            this.gameId = gameId;
        }
    }

    private final Services services;
    private final Catalog catalog;
    private final MatchSettings settings;
    private final EffectRegistry registry;
    private final MatchHooks hooks;
    private final BanPolicy bans;
    private final Supplier<JsonNode> botDeck;
    private final RandomGenerator rng;
    private final Map<String, List<QueueEntry>> queues = new LinkedHashMap<>();
    private final Map<String, Pairing> pendingPairings = new HashMap<>();
    private final Map<String, GameSession> activeSessions = new HashMap<>();
    private final Map<String, String> sessionIdsByPlayer = new HashMap<>();
    private final List<AutoCloseable> noop = List.of();

    public MatchManager(Services services, MatchSettings settings, EffectRegistry registry, MatchHooks hooks,
                        BanPolicy bans, Supplier<JsonNode> botDeck, RandomGenerator rng) {
        this.services = services;
        this.catalog = services.catalog();
        this.settings = settings;
        this.registry = registry;
        this.hooks = hooks;
        this.bans = bans;
        this.botDeck = botDeck;
        this.rng = rng;
    }

    public MatchSettings settings() {
        return settings;
    }

    // ---- lookups ---------------------------------------------------------------------------------------

    public synchronized GameSession sessionByPlayer(String accountId) {
        String gameId = sessionIdsByPlayer.get(accountId);
        if (gameId != null) {
            GameSession s = activeSessions.get(gameId);
            if (s != null) return s;
        }
        for (var e : activeSessions.entrySet()) {
            if (e.getValue().players().containsKey(accountId)) {
                sessionIdsByPlayer.put(accountId, e.getKey());
                return e.getValue();
            }
        }
        return null;
    }

    public synchronized int activeSessionCount() {
        return activeSessions.size();
    }

    public synchronized int queuedCount() {
        return queues.values().stream().mapToInt(List::size).sum();
    }

    public synchronized int pendingPairingCount() {
        return pendingPairings.size();
    }

    public synchronized void removeSessionByPlayer(String accountId) {
        GameSession s = sessionByPlayer(accountId);
        if (s != null) {
            LOG.info("[SessionManager] Removing stale/finished session {} for player {}", s.gameId, accountId);
            removeSession(s.gameId);
        }
    }

    /** Session callback and stale cleanup: forgets the session and releases its resources. */
    public synchronized void removeSession(String gameId) {
        GameSession s = activeSessions.remove(gameId);
        if (s == null) return;
        sessionIdsByPlayer.values().removeIf(g -> g.equals(gameId));
        try {
            s.cleanup();
        } catch (RuntimeException e) {
            LOG.error("[SessionManager] Error cleaning up session {}", gameId, e);
        }
    }

    // ---- GameChatRouter / FriendMatchStarter -------------------------------------------------------------

    @Override
    public List<ClientContext> recipients(String senderAccountId, String gameId) {
        GameSession s = sessionByPlayer(senderAccountId);
        if (s == null || !s.gameId.equals(gameId)) return List.of();
        List<ClientContext> out = new ArrayList<>();
        for (GamePlayer p : s.players().values()) {
            if (p instanceof NetworkPlayer n && n.client() != null && !out.contains(n.client())) out.add(n.client());
        }
        return out;
    }

    @Override
    public boolean isInMatch(String accountId) {
        return sessionByPlayer(accountId) != null;
    }

    /** Friend challenge accepted: pair both clients ("Friend" queue) and run the ready check. */
    @Override
    public synchronized void start(ClientContext challenger, JsonNode challengerDeck, ClientContext accepter,
                                   JsonNode accepterDeck, JsonNode matchOptions) {
        for (var d : new Object[][] {{challenger, challengerDeck}, {accepter, accepterDeck}}) {
            ClientContext c = (ClientContext) d[0];
            JsonNode deck = (JsonNode) d[1];
            Optional<String> ban = bans.refusalText(deck);
            if (ban.isPresent()) {
                failChallenge(challenger, ban.get());
                failChallenge(accepter, ban.get());
                return;
            }
            if (!BotDecks.playable(catalog, deck)) {
                failChallenge(challenger, "Your opponent's deck is not valid.");
                failChallenge(accepter, "Your deck is not valid.");
                return;
            }
        }
        String gameId = UUID.randomUUID().toString();
        LOG.info("[Matchmaking] Challenge accepted! Creating game {} for {} vs {}", gameId, challenger.account().username(),
                accepter.account().username());
        Pairing p = new Pairing(gameId);
        p.players.put(challenger.account().accountId(), new PairingPlayer(challenger, challengerDeck, false));
        p.players.put(accepter.account().accountId(), new PairingPlayer(accepter, accepterDeck, false));
        p.solo = false;
        p.options = matchOptions;
        p.queueName = "Friend";
        pendingPairings.put(gameId, p);
        dispatchReadyCheck(gameId, "Friend", List.of(challenger, accepter), settings.readyCheckDelayMs);
    }

    private static void failChallenge(ClientContext c, String text) {
        c.send("MatchRequestWithSpecificClientFailed", obj("failureType", obj("id", text)), 0);
    }

    // ---- queue ---------------------------------------------------------------------------------------------

    private boolean refuseBannedDeck(ClientContext client, JsonNode deck) {
        Optional<String> text = bans.refusalText(deck);
        if (text.isEmpty()) return false;
        client.send("MatchRequestError", obj("errorMsg", obj("id", text.get())), 0);
        return true;
    }

    /** Adds a client to the matchmaking queue for a format; pairs it with a waiting player or arms the bot fallback. */
    public synchronized void addToQueue(ClientContext client, String queueName, JsonNode deck, JsonNode options) {
        if (queueName == null || queueName.length() > MAX_QUEUE_NAME_LEN) {
            LOG.warn("[Matchmaking] Rejecting oversized/invalid queueName from {}", client.remoteAddress());
            return;
        }
        if (refuseBannedDeck(client, deck)) return;
        if (!checkThemeDeck(client, queueName, deck)) return;
        if (!BotDecks.playable(catalog, deck)) {
            client.send("MatchRequestError", obj("errorMsg", obj("id", "You need a valid deck to play.")), 0);
            return;
        }
        // Clean up any existing queue registrations and active sessions for this client first.
        removeFromQueue(client, false);
        removeSessionByPlayer(client.account().accountId());
        LOG.info("[Matchmaking] Player {} ({}) entering queue '{}'", client.account().username(),
                client.account().accountId(), queueName);
        // MatchQueueEntered transitions the client state machine to Statuses.Waiting.
        Map<String, Object> entered = obj("estimatedWaitTime", 10);
        List<QueueEntry> queue = queues.computeIfAbsent(queueName, k -> new ArrayList<>());
        if (!queue.isEmpty()) {
            QueueEntry opp = queue.remove(0);
            if (queue.isEmpty()) queues.remove(queueName);
            client.send("MatchQueueEntered", entered, 0);
            String gameId = UUID.randomUUID().toString();
            LOG.info("[Matchmaking] Pair matched! Creating multiplayer game {} for {} vs {}", gameId,
                    client.account().username(), opp.client().account().username());
            Pairing p = new Pairing(gameId);
            p.players.put(client.account().accountId(), new PairingPlayer(client, deck, false));
            p.players.put(opp.client().account().accountId(), new PairingPlayer(opp.client(), opp.deck(), false));
            p.solo = false;
            p.options = options;
            p.queueName = queueName;
            pendingPairings.put(gameId, p);
            dispatchReadyCheck(gameId, queueName, List.of(client, opp.client()), settings.readyCheckDelayMs);
        } else {
            queue.add(new QueueEntry(client, deck, options));
            client.send("MatchQueueEntered", entered, 0);
            scheduleBotFallback(client, queueName, deck, options);
        }
    }

    /** The Theme format accepts only an unmodified approved theme deck. */
    private boolean checkThemeDeck(ClientContext client, String queueName, JsonNode deck) {
        if (!(services.deckValidator() instanceof RulesDeckValidator rules)) return true;
        String formatGuid = rules.formats().resolveFormatGuid(queueName);
        if (!(FormatManager.THEME.equals(formatGuid) || queueName.toLowerCase().endsWith("themedeck"))) return true;
        try {
            Map<String, Integer> owned = services.players().ownedCounts(client.account().accountId());
            var rows = rules.validate(deck, owned, List.of(FormatManager.THEME));
            if (!rows.isEmpty() && rows.get(0).valid()) return true;
        } catch (java.sql.SQLException e) {
            LOG.error("[Matchmaking] theme deck validation failed", e);
        }
        client.send("MatchRequestError", obj("errorMsg", obj("id", "Choose an unmodified, approved theme deck.")), 0);
        return false;
    }

    /** Removes a client from all format queues; optionally tells it (MatchQueueLeft). */
    public synchronized void removeFromQueue(ClientContext client, boolean sendLeft) {
        boolean removed = false;
        for (var it = queues.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            int before = e.getValue().size();
            e.getValue().removeIf(q -> q.client() == client);
            if (e.getValue().size() < before) removed = true;
            if (e.getValue().isEmpty()) it.remove(); // prune empty queues so the map can't grow under churn
        }
        if (removed) {
            LOG.info("[Matchmaking] Removed player {} from all queues.", client.account() == null ? "?" : client.account().username());
            if (sendLeft) client.send("MatchQueueLeft", obj(), 0);
        }
    }

    // ---- bot fallback ----------------------------------------------------------------------------------------

    private void scheduleBotFallback(ClientContext client, String queueName, JsonNode deck, JsonNode options) {
        double delay = settings.botQueueDelaySeconds;
        if (delay <= 0) return;
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(Math.round(delay * 1000));
            } catch (InterruptedException e) {
                return;
            }
            botFallback(client, queueName, deck, options, delay);
        });
    }

    private synchronized void botFallback(ClientContext client, String queueName, JsonNode deck, JsonNode options, double delay) {
        List<QueueEntry> entries = queues.getOrDefault(queueName, List.of());
        if (entries.stream().noneMatch(q -> q.client() == client)) return; // matched with a human, cancelled or re-queued
        if (client.account() == null) return;
        removeFromQueue(client, false);
        String gameId = UUID.randomUUID().toString();
        LOG.info("[Matchmaking] Queue '{}' empty after {}s - pairing {} with the AI bot (game {})", queueName, delay,
                client.account().username(), gameId);
        Pairing p = new Pairing(gameId);
        p.players.put(client.account().accountId(), new PairingPlayer(client, deck, false));
        p.players.put(BOT_ACCOUNT_ID, new PairingPlayer(null, botDeck.get(), true));
        p.solo = true;
        p.solitaireId = "basic_bot_id";
        p.options = options;
        p.queueName = queueName;
        pendingPairings.put(gameId, p);
        dispatchReadyCheck(gameId, queueName, List.of(client), settings.botReadyCheckDelayMs);
    }

    // ---- solo ----------------------------------------------------------------------------------------------------

    /** Starts a practice match against the AI (RequestSinglePlayerMatch). */
    public synchronized void startSoloMatch(ClientContext client, JsonNode deck, String solitaireId, JsonNode options, int requestId) {
        if (refuseBannedDeck(client, deck)) return;
        if (!BotDecks.playable(catalog, deck)) {
            client.send("MatchRequestError", obj("errorMsg", obj("id", "You need a valid deck to play.")), 0);
            return;
        }
        removeFromQueue(client, false);
        removeSessionByPlayer(client.account().accountId());
        String gameId = UUID.randomUUID().toString();
        LOG.info("[Matchmaking] Starting Single Player match {} for {} vs AI ({})", gameId, client.account().username(), solitaireId);
        Pairing p = new Pairing(gameId);
        p.players.put(client.account().accountId(), new PairingPlayer(client, deck, false));
        p.players.put(BOT_ACCOUNT_ID, new PairingPlayer(null, botDeck.get(), true));
        p.solo = true;
        p.solitaireId = solitaireId;
        p.options = options;
        p.queueName = "SinglePlayer";
        pendingPairings.put(gameId, p);
        // The RPC reply to RequestSinglePlayerMatch carries ConfirmReadyForMatch (Python sends it with the request id).
        client.send("ConfirmReadyForMatch", obj("queueName", "SinglePlayer", "gameID", gameId,
                "path", "game_route_" + gameId.substring(0, 8)), requestId);
        if (settings.autoConfirmReady) {
            // The real client's confirmation is never answered by the Python server (its handler is absent), so a
            // solo match would never start; confirm on its behalf like the versus ready check does.
            Thread.ofVirtual().start(() -> {
                sleepQuietly(settings.botReadyCheckDelayMs);
                confirmReady(client, gameId);
            });
        }
    }

    // ---- live bracket ----------------------------------------------------------------------------------------

    /**
     * Launches a live-bracket matchup (Python LiveTournament._start_or_forfeit): a pairing with queue
     * "Tournament_<tournamentId>", registered in the pending pairings, then the ready check. Returns false when a
     * seat has no live client or an unplayable/banned deck, so nothing was registered.
     */
    public synchronized boolean startTournamentMatch(String gameId, String tournamentId, List<TournamentMatchRunner.Seat> seats,
                                                     GameSession.GameResultListener listener) {
        if (seats.size() != 2 || tournamentId == null || tournamentId.isEmpty()) return false;
        for (TournamentMatchRunner.Seat seat : seats) {
            ClientContext c = seat.client();
            if (c == null || c.account() == null || seat.deck() == null) return false;
            if (bans.refusalText(seat.deck()).isPresent() || !BotDecks.playable(catalog, seat.deck())) return false;
        }
        String queueName = "Tournament_" + tournamentId;
        Pairing p = new Pairing(gameId);
        for (TournamentMatchRunner.Seat seat : seats) {
            removeFromQueue(seat.client(), false);
            removeSessionByPlayer(seat.accountId());
            p.players.put(seat.accountId(), new PairingPlayer(seat.client(), seat.deck(), false));
        }
        p.solo = false;
        p.options = null;
        p.queueName = queueName;
        p.tournamentId = tournamentId;
        p.tournamentListener = listener;
        pendingPairings.put(gameId, p);
        LOG.info("[Tournament] Launching game {} for tournament {}", gameId, tournamentId);
        dispatchReadyCheck(gameId, queueName, List.of(seats.get(0).client(), seats.get(1).client()), settings.readyCheckDelayMs);
        return true;
    }

    /** Bracket aborted the matchup: drops a pending pairing and stops the running game, if any. */
    public synchronized void abortTournamentMatch(String gameId) {
        pendingPairings.remove(gameId);
        removeSession(gameId);
    }

    // ---- ready check -----------------------------------------------------------------------------------------------

    private void dispatchReadyCheck(String gameId, String queueName, List<ClientContext> clients, long delayMs) {
        Thread.ofVirtual().start(() -> {
            sleepQuietly(delayMs);
            try {
                Map<String, Object> confirm = obj("queueName", queueName, "gameID", gameId,
                        "path", "game_route_" + gameId.substring(0, 8));
                for (ClientContext c : clients) c.send("ConfirmReadyForMatch", confirm, 0);
                LOG.info("[Matchmaking] Dispatched ConfirmReadyForMatch to all players for game {}", gameId);
                // The client has no active ConfirmReadyForMatch listener during normal play, so readiness is
                // confirmed on its behalf to trigger MatchFound cleanly.
                if (settings.autoConfirmReady) for (ClientContext c : clients) confirmReady(c, gameId);
            } catch (RuntimeException e) {
                LOG.error("[Matchmaking] Error in matchmaking transition", e);
            }
        });
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Ready-check confirmation from a client (or on its behalf). */
    public void confirmReady(ClientContext client, String gameId) {
        boolean allReady = false;
        synchronized (this) {
            Pairing p = pendingPairings.get(gameId);
            if (p == null) {
                LOG.warn("[Matchmaking] Received ready confirmation for non-existent pairing {}", gameId);
                return;
            }
            PairingPlayer pp = p.players.get(client.account().accountId());
            if (pp == null) {
                LOG.warn("[Matchmaking] Player {} not part of pairing {}", client.account().username(), gameId);
                return;
            }
            pp.ready = true;
            LOG.info("[Matchmaking] Player {} confirmed ready for game {}", client.account().username(), gameId);
            allReady = p.players.values().stream().allMatch(x -> x.ready);
        }
        if (allReady) transitionToGame(gameId);
    }

    /** Instantiates the GameSession and sends MatchFound to start the scene transition. */
    private void transitionToGame(String gameId) {
        GameSession session;
        synchronized (this) {
            Pairing p = pendingPairings.remove(gameId);
            if (p == null) return;
            // Backstop for a ban that landed while a deck sat in a queue or a ready check.
            for (PairingPlayer pp : p.players.values()) {
                if (pp.client == null) continue;
                Optional<String> ban = bans.refusalText(pp.deck);
                if (ban.isPresent()) {
                    for (PairingPlayer h : p.players.values()) {
                        if (h.client != null) h.client.send("MatchRequestError", obj("errorMsg",
                                obj("id", "A deck in this match contains a card banned on this server.")), 0);
                    }
                    return;
                }
            }
            LOG.info("[Matchmaking] All players ready! Transitioning game {} to active gameplay", gameId);
            List<GamePlayer> seats = new ArrayList<>();
            for (var e : p.players.entrySet()) {
                PairingPlayer pp = e.getValue();
                if (pp.client == null) {
                    seats.add(new AiPlayer(e.getKey(), AiPlayer.randomName(rng), pp.deck, AvatarItems.defaults(catalog)));
                } else {
                    List<String> avatar = hooks.avatarItems(e.getKey());
                    seats.add(new NetworkPlayer(pp.client, pp.deck, avatar != null ? avatar : AvatarItems.defaults(catalog)));
                }
            }
            session = new GameSession(gameId, p.solo, p.queueName, seats, catalog, registry, settings, hooks, rng, this::removeSession);
            if (p.tournamentListener != null) session.attachTournament(p.tournamentId, p.tournamentListener);
            activeSessions.put(gameId, session);
            for (String id : session.players().keySet()) sessionIdsByPlayer.put(id, gameId);
        }
        session.start();
    }

    // ---- disconnect cleanup ---------------------------------------------------------------------------------------

    /** Connection teardown: leaves queues, drops ready-check pairings, detaches from a live game for reconnect. */
    public void clientGone(ClientContext client) {
        if (client.account() == null) return;
        String id = client.account().accountId();
        synchronized (this) {
            removeFromQueue(client, false);
            pendingPairings.values().removeIf(p -> p.players.values().stream().anyMatch(x -> x.client == client));
        }
        GameSession s = sessionByPlayer(id);
        if (s != null && s.phase() != GamePhase.GAME_OVER) {
            s.onPlayerDisconnect(id, client);
        } else {
            removeSessionByPlayer(id);
        }
    }

    /** Sessions of unfinished games (tests, diagnostics). */
    public synchronized List<GameSession> sessions() {
        return new ArrayList<>(activeSessions.values());
    }
}
