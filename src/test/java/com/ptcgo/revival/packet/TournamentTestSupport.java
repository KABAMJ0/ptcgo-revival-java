package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.db.TournamentRepository;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.tournament.AsyncTournamentMatchmaker;
import com.ptcgo.revival.tournament.LiveTournamentManager;
import com.ptcgo.revival.tournament.ManualScheduler;
import com.ptcgo.revival.tournament.Scheduler;
import com.ptcgo.revival.tournament.TournamentDeckChecker;
import com.ptcgo.revival.tournament.TournamentMatchRunner;
import com.ptcgo.revival.tournament.TournamentModule;

import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Harness for the tournament integration tests: one server, any number of logged-in sockets, seeded tournaments. */
final class TournamentTestSupport implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long HOUR = 3_600_000L;

    final Services services;
    final TournamentModule tournaments;
    final GameServer server;
    final int port;
    final ManualScheduler scheduler = new ManualScheduler();
    final List<String[]> enqueued = new ArrayList<>(); // {accountId-less: queueName, tournamentId, entryId}
    final boolean matchmaking;

    TournamentTestSupport(TournamentMatchRunner runner, boolean matchmaking, TournamentDeckChecker checker) throws Exception {
        this.matchmaking = matchmaking;
        services = TestServices.create(new Config());
        AsyncTournamentMatchmaker mm = !matchmaking ? AsyncTournamentMatchmaker.UNAVAILABLE : new AsyncTournamentMatchmaker() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void enqueue(ClientContext client, String queueName, JsonNode deck, String tournamentId, String entryId) {
                enqueued.add(new String[] {queueName, tournamentId, entryId});
                client.send("MatchQueueEntered", Map.of("queueName", queueName), 0);
            }
        };
        Scheduler sch = scheduler;
        tournaments = TournamentModule.create(services, runner, mm,
                checker != null ? checker : TournamentDeckChecker.of(services.deckValidator()), sch,
                new LiveTournamentManager.Timings(new long[] {1, 2, 3}, 3), new Random(7));
        List<PacketHandler> groups = new ArrayList<>();
        groups.add(new AuthHandler());
        groups.addAll(tournaments.handlers());
        groups.add(new DataSyncHandler());
        groups.add(hooks());
        server = new GameServer(services, new PacketRouter(groups), null);
        port = server.start(0);
    }

    /** Stands in for the ClientConnection wiring, which this change does not edit. */
    private static PacketHandler hooks() {
        return () -> {
            Map<String, PacketHandler.MessageHandler> m = new HashMap<>();
            m.put("HookDisconnectNow", (c, v, rid, f) -> TournamentModule.of(c).disconnectNow(c));
            m.put("HookDisconnectAsync", (c, v, rid, f) -> TournamentModule.onDisconnect(c));
            return m;
        };
    }

    SocialTestSupport.Client login(String user) throws Exception {
        SocialTestSupport.Client c = new SocialTestSupport.Client(new Socket("127.0.0.1", port));
        c.send("AuthenticateGASAuthToken", Map.of("userID", user, "token", "pw"), 1);
        JsonNode ok = c.next();
        if (!"AuthenticationSuccessful".equals(ok.get("name").asText())) throw new IllegalStateException(ok.toString());
        c.accountId = ok.get("value").get("account").get("accountID").asText();
        c.name = user;
        for (int i = 0; i < 11; i++) c.next(); // rest of the 12-message login burst
        return c;
    }

    /** Saves a deck (deck_data in the {@code piles} shape) for the account and returns its id. */
    String deckFor(SocialTestSupport.Client c, String name) throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        ObjectNode deck = JSON.createObjectNode();
        deck.put("deckID", id);
        deck.put("deckName", name);
        ArrayNode cards = deck.putObject("piles").putArray("deck");
        for (int i = 0; i < 60; i++) cards.add("00000000-0000-0000-0000-00000000000" + (i % 10));
        deck.putArray("attributes");
        services.players().saveDeck(c.accountId, id, name, deck, false);
        return id;
    }

    void wallet(SocialTestSupport.Client c, int coins, int gems, int tickets) throws Exception {
        services.players().updateWallet(c.accountId, coins, gems, tickets);
    }

    int coins(SocialTestSupport.Client c) throws Exception {
        return services.players().wallet(c.accountId).coins();
    }

    TournamentRepository repo() {
        return tournaments.repository();
    }

    /** A definition: open now unless {@code open} is false (then resolved and past entry). */
    static ObjectNode definition(String name, int maxSize, boolean open, int fee) {
        long now = System.currentTimeMillis();
        ObjectNode d = JSON.createObjectNode();
        d.put("name", name);
        d.put("format", "Unlimited");
        d.put("maxSize", maxSize);
        d.put("roundLength", 30);
        d.put("delayBetweenRounds", 5);
        d.put("previewTime", now - 10 * HOUR);
        d.put("startTime", now - 9 * HOUR);
        d.put("entryClosingTime", open ? now + 9 * HOUR : now - 5 * HOUR);
        d.put("resolutionTime", open ? now + 10 * HOUR : now - 4 * HOUR);
        d.put("disappearTime", open ? now + 20 * HOUR : now + 20 * HOUR);
        d.put("maxRuns", 2);
        ObjectNode run = d.putObject("run");
        run.put("maxWins", 2);
        run.put("maxLosses", 2);
        if (fee > 0) {
            ObjectNode f = run.putArray("entryFee").addObject();
            f.put("currency", "coins");
            f.put("amount", fee);
        }
        ArrayNode prizes = run.putArray("prizeTable");
        prizes.add(prize(1, 1, 500));
        prizes.add(prize(2, 2, 100));
        ObjectNode lb = d.putObject("leaderboard");
        lb.put("winValue", 3);
        lb.putArray("prizeTable").add(prize(1, 1, 1000)).add(prize(2, 5, 200));
        return d;
    }

    static ObjectNode prize(int start, int end, int tokens) {
        ObjectNode p = JSON.createObjectNode();
        p.put("start", start);
        p.put("end", end);
        ObjectNode r = p.putArray("rewards").addObject();
        r.put("name", "Prize");
        r.put("rewardType", "Tokens");
        r.put("rewardAmount", tokens);
        r.put("rewardCurrency", "coins");
        return p;
    }

    /** Stores the definition and reloads the module's cache; returns the tournament id. */
    String seed(String id, ObjectNode definition) throws Exception {
        repo().upsertTournament(definition, id, true);
        tournaments.reload();
        return id;
    }

    @Override
    public void close() throws Exception {
        server.close();
    }

    // ---- reading helpers --------------------------------------------------------------------------

    /** Reads (and discards) messages until one with this name arrives; returns its value. */
    static JsonNode until(SocialTestSupport.Client c, String name) throws Exception {
        for (int i = 0; i < 60; i++) {
            JsonNode m = c.next();
            if (name.equals(m.path("name").asText())) return m.get("value");
        }
        throw new AssertionError("never saw " + name);
    }

    /** Messages (name and value) that arrive until the connection is quiet for a moment. */
    static List<JsonNode> drain(SocialTestSupport.Client c) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        c.socket.setSoTimeout(400);
        try {
            while (true) out.add(c.next());
        } catch (java.net.SocketTimeoutException e) {
            return out;
        } finally {
            c.socket.setSoTimeout(5000);
        }
    }

    static List<String> names(List<JsonNode> msgs) {
        return msgs.stream().map(m -> m.path("name").asText()).toList();
    }

    static JsonNode first(List<JsonNode> msgs, String name) {
        return msgs.stream().filter(m -> name.equals(m.path("name").asText())).findFirst().map(m -> m.get("value"))
                .orElseThrow(() -> new AssertionError("no " + name + " in " + names(msgs)));
    }

    static List<JsonNode> all(List<JsonNode> msgs, String name) {
        return msgs.stream().filter(m -> name.equals(m.path("name").asText())).map(m -> m.get("value")).toList();
    }
}
