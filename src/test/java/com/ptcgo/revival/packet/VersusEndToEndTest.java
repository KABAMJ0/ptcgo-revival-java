package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.auth.CasTickets;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.db.QuestRepository;
import com.ptcgo.revival.db.VersusRepository;
import com.ptcgo.revival.deck.DeckValidator;
import com.ptcgo.revival.game.TestCatalog;
import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.engine.MatchSettings;
import com.ptcgo.revival.game.match.BanPolicy;
import com.ptcgo.revival.game.match.VersusModule;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.net.WargCodec;
import com.ptcgo.revival.progression.Progression;
import com.ptcgo.revival.progression.Seasons;
import com.ptcgo.revival.social.SocialModule;
import java.io.DataInputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Protocol-level end to end: scripted clients log in, queue, and play whole matches to GameCompleted. */
class VersusEndToEndTest {
    static final class Harness implements AutoCloseable {
        final Services services;
        final VersusModule versus;
        final GameServer server;
        final int port;

        Harness(MatchSettings settings) throws Exception {
            Path tmp = Files.createTempFile("ptcgo-versus", ".db");
            tmp.toFile().deleteOnExit();
            Database db = new Database("jdbc:sqlite:" + tmp);
            db.migrate();
            services = new Services(new Config(), new AccountRepository(db), new PlayerRepository(db), new CasTickets(),
                    new ClientRegistry(), TestCatalog.catalog(), DeckValidator.PERMISSIVE,
                    new Progression(new QuestRepository(db), new VersusRepository(db), Seasons.loadBundled(),
                            new com.ptcgo.revival.db.DailyLoginRepository(db,
                                    com.ptcgo.revival.progression.DailyRewards.loadBundled(), TestCatalog.catalog(),
                                    java.time.Instant::now, new Random(1))),
                    null);
            versus = VersusModule.create(services, settings, EffectRegistry.empty(), new VersusHooks(services), BanPolicy.NONE,
                    () -> TestCatalog.lightningDeck(), new Random(42));
            SocialModule social = SocialModule.create(services, versus.manager(), versus.manager());
            List<PacketHandler> groups = new ArrayList<>();
            groups.add(new AuthHandler());
            groups.addAll(social.handlers());
            groups.addAll(versus.handlers());
            server = new GameServer(services, new PacketRouter(groups), null);
            port = server.start(0);
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }

    /** A scripted player: answers every selection the server offers from the legal actions it is given. */
    static final class Bot implements Runnable {
        final Socket socket;
        final DataInputStream in;
        final String name;
        String accountId;
        volatile JsonNode completed;
        volatile Throwable error;
        final List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<JsonNode> chat = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile String gameId;
        volatile boolean concedeAfterFirstTurnOffer;
        int placedBench;

        Bot(int port, String name) throws Exception {
            this.name = name;
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(30000);
            in = new DataInputStream(socket.getInputStream());
        }

        void send(String n, Object payload, int rid) throws Exception {
            synchronized (socket) {
                socket.getOutputStream().write(WargCodec.frame(
                        WargCodec.JSON.writeValueAsBytes(Map.of("name", n, "value", payload)), rid, 0));
                socket.getOutputStream().flush();
            }
        }

        JsonNode next() throws Exception {
            byte[] h = new byte[WargCodec.HEADER_SIZE];
            in.readFully(h);
            int[] hd = WargCodec.decodeHeader(h);
            byte[] body = new byte[hd[0]];
            in.readFully(body);
            return WargCodec.parseJson(WargCodec.inflateIfNeeded(body, hd[2], 1 << 22));
        }

        void login() throws Exception {
            send("AuthenticateGASAuthToken", Map.of("userID", name, "token", "pw"), 1);
            JsonNode ok = next();
            assertEquals("AuthenticationSuccessful", ok.get("name").asText());
            accountId = ok.get("value").get("account").get("accountID").asText();
            while (!"DynamicVersions".equals(next().get("name").asText())) { /* login burst */ }
        }

        void queue(String queueName) throws Exception {
            send("RequestQueueMatch", Map.of("queueName", queueName, "deck", TestCatalog.lightningDeck(),
                    "clientOptions", Map.of()), 5);
        }

        @Override
        public void run() {
            try {
                int after = 0;
                while (completed == null || (!seen.contains("AccountUpdated") && after++ < 6)) {
                    JsonNode m = next();
                    if (!m.hasNonNull("name")) continue; // the empty RPC reply
                    String n = m.get("name").asText();
                    seen.add(n);
                    JsonNode v = m.get("value");
                    switch (n) {
                        case "MatchFound" -> {
                            gameId = v.get("gameID").asText();
                            send("PlayerReady", Map.of(), 0);
                        }
                        case "NotifyGameChat" -> chat.add(v);
                        case "SequenceMessage" -> onSequence(v.get("msg"));
                        default -> { }
                    }
                }
            } catch (SocketTimeoutException e) {
                error = e;
            } catch (java.io.EOFException | java.net.SocketException e) {
                // closed by the test
            } catch (Throwable t) {
                error = t;
            }
        }

        private void onSequence(JsonNode msg) throws Exception {
            String n = msg.get("name").asText();
            JsonNode v = msg.get("value");
            seen.add(n);
            switch (n) {
                case "CoinFlipChoiceRequired" -> send("GameCustomChoice", Map.of("selection", 0, "counter", v.get("counter").asInt()), 0);
                case "GoFirstChoiceRequired" -> send("GameCustomChoice", Map.of("selection", 0, "counter", v.get("counter").asInt()), 0);
                case "CustomChoiceRequired" -> send("GameCustomChoice", Map.of("selection", 0, "counter", v.get("counter").asInt()), 0);
                case "SelectionWithTargetsRequired" -> answerPick(v);
                case "SelectionWithTargetsAndActionsRequired" -> answerMain(v);
                case "GameCompletedMessage" -> completed = v;
                default -> { }
            }
        }

        private void answerPick(JsonNode v) throws Exception {
            int counter = v.get("counter").asInt();
            String type = v.get("targetType").asText();
            if (type.equals("PrizeCardTargetInformation")) {
                JsonNode node = v.get("targetMap").elements().next().get(0);
                int count = node.get("numberToSelect").asInt();
                List<String> picks = new ArrayList<>();
                for (int i = 0; i < count; i++) picks.add(node.get("validTargets").get(i).asText());
                send("SelectionWithTargets", Map.of("selection", Map.of("targetResponses", List.of(Map.of("entityList", picks))),
                        "counter", counter), 0);
                return;
            }
            List<String> cands = new ArrayList<>();
            v.get("targetMap").fieldNames().forEachRemaining(cands::add);
            boolean bench = type.equals("InitialBenchedTargetInformation");
            if (bench && (placedBench >= 1 || cands.isEmpty())) {
                send("SelectionWithTargets", java.util.Collections.singletonMap("counter", counter), 0); // Done
                return;
            }
            if (bench) placedBench++;
            send("SelectionWithTargets", Map.of("selection", Map.of("entityID", cands.get(0)), "counter", counter), 0);
        }

        private void answerMain(JsonNode v) throws Exception {
            int counter = v.get("counter").asInt();
            if (concedeAfterFirstTurnOffer) {
                send("ResignGame", Map.of("gameID", gameId, "gameNum", 1), 0);
                return;
            }
            JsonNode best = null;
            int bestRank = 99;
            for (JsonNode e : v.get("targetMap")) {
                String d = e.get("selectableAction").get("description").asText();
                int rank = switch (d) {
                    case "DefaultPokemonPlayAbility" -> 0;
                    case "EvolvePokemonPlayAbility" -> 1;
                    case "UseTrainerCard" -> 2;
                    case "DefaultEnergyPlayAbility" -> 3;
                    case "UsePokemonAttack" -> 4;
                    default -> 99;
                };
                if (rank < bestRank) {
                    best = e;
                    bestRank = rank;
                }
            }
            if (best == null) {
                send("SelectionWithTargetsAndActions", java.util.Collections.singletonMap("counter", counter), 0);
                return;
            }
            List<Object> head = List.of(best.get("entityID").asText(), best.get("selectableAction").get("actionID").asText());
            List<Object> responses = new ArrayList<>();
            JsonNode infos = best.get("targetInfoLst");
            if (infos.size() > 0) {
                responses.add(Map.of("entityList", List.of(infos.get(0).get("validTargets").get(0).asText())));
            }
            send("SelectionWithTargetsAndActions", Map.of("selection", List.of(head, responses), "counter", counter), 0);
        }

        void close() throws Exception {
            socket.close();
        }
    }

    private static Thread start(Bot b) {
        return Thread.ofVirtual().start(b);
    }

    private static void await(Bot b, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (b.completed == null && b.error == null && System.currentTimeMillis() < end) Thread.sleep(20);
        if (b.error != null) throw new AssertionError("bot " + b.name + " failed", b.error);
        assertNotNull(b.completed, "bot " + b.name + " never got GameCompleted; saw " + b.seen.size() + " msgs, last "
                + b.seen.subList(Math.max(0, b.seen.size() - 6), b.seen.size()));
    }

    @Test
    void queueFallsBackToTheBotAndTheMatchPlaysToCompletion() throws Exception {
        try (Harness h = new Harness(MatchSettings.fast())) {
            Bot bot = new Bot(h.port, "Ash");
            bot.login();
            bot.queue("Standard");
            start(bot);
            await(bot, 90_000);
            JsonNode done = bot.completed;
            String winner = done.get("winner").asText();
            String loser = done.get("loser").asText();
            assertEquals(bot.gameId, done.get("gameID").asText());
            assertTrue(winner.equals(bot.accountId) || loser.equals(bot.accountId));
            JsonNode params = done.get("additionalParameters");
            assertTrue(params.has("GameResult"));
            boolean won = winner.equals(bot.accountId);
            assertEquals(won ? "Win" : "Loss", params.get("GameResult").get("id").asText());
            assertEquals(won ? 200 : 0, done.get("coins").asInt());
            Thread.sleep(300);
            var progress = h.services.progression().versus().progress(bot.accountId, h.services.progression().seasons().activeId());
            assertEquals(won ? 5 : 0, progress.points());
            // wallet and account were refreshed for the HUD and the ladder
            assertTrue(bot.seen.contains("CurrentWallet"));
            assertTrue(bot.seen.contains("AccountUpdated"));
            assertTrue(bot.seen.contains("MatchQueueEntered"));
            assertTrue(bot.seen.contains("ConfirmReadyForMatch"));
            Thread.sleep(300);
            assertEquals(0, h.versus.manager().activeSessionCount(), "session was removed");
            bot.close();
        }
    }

    @Test
    void twoClientsArePairedAndPlayAndChat() throws Exception {
        MatchSettings s = MatchSettings.fast();
        s.botQueueDelaySeconds = 0; // wait for a human
        try (Harness h = new Harness(s)) {
            Bot a = new Bot(h.port, "Misty"), b = new Bot(h.port, "Brock");
            a.login();
            b.login();
            a.queue("Standard");
            start(a);
            Thread.sleep(100);
            b.queue("Standard");
            start(b);
            await(a, 90_000);
            await(b, 90_000);
            assertEquals(a.completed.get("winner").asText(), b.completed.get("winner").asText());
            assertNotEquals(a.completed.get("winner").asText(), a.completed.get("loser").asText());
            String winner = a.completed.get("winner").asText();
            assertEquals(a.gameId, b.gameId);
            Thread.sleep(300);
            var vr = h.services.progression().versus();
            String seasonId = h.services.progression().seasons().activeId();
            assertEquals(5, vr.progress(winner, seasonId).points());
            String loser = winner.equals(a.accountId) ? b.accountId : a.accountId;
            assertEquals(0, vr.progress(loser, seasonId).points());
            a.close();
            b.close();
        }
    }

    @Test
    void cancelLeavesTheQueue() throws Exception {
        MatchSettings s = MatchSettings.fast();
        s.botQueueDelaySeconds = 0;
        try (Harness h = new Harness(s)) {
            Bot a = new Bot(h.port, "Gary");
            a.login();
            a.queue("Standard");
            a.next();
            JsonNode entered = a.next();
            assertEquals("MatchQueueEntered", entered.get("name").asText());
            assertEquals(1, h.versus.manager().queuedCount());
            a.send("CancelMatchRequest", Map.of(), 6);
            assertEquals("MatchQueueLeft", a.next().get("name").asText());
            assertEquals(0, h.versus.manager().queuedCount());
            a.close();
        }
    }

    @Test
    void resignEndsTheMatchForBothAndAwardsTheOpponent() throws Exception {
        MatchSettings s = MatchSettings.fast();
        s.botQueueDelaySeconds = 0;
        try (Harness h = new Harness(s)) {
            Bot a = new Bot(h.port, "Red"), b = new Bot(h.port, "Blue");
            a.login();
            b.login();
            a.concedeAfterFirstTurnOffer = true;
            b.concedeAfterFirstTurnOffer = true;
            a.queue("Standard");
            start(a);
            Thread.sleep(100);
            b.queue("Standard");
            start(b);
            await(a, 60_000);
            await(b, 60_000);
            String reason = a.completed.get("endOfGameText").get("id").asText();
            assertTrue(reason.endsWith("conceded"), reason);
            a.close();
            b.close();
        }
    }

    @Test
    void disconnectedPlayerForfeitsAfterTheGraceWindowAndTheOpponentIsAwarded() throws Exception {
        MatchSettings s = MatchSettings.fast();
        s.botQueueDelaySeconds = 0;
        s.reconnectGraceSeconds = 1.0;
        try (Harness h = new Harness(s)) {
            Bot a = new Bot(h.port, "Dawn"), b = new Bot(h.port, "May");
            a.login();
            b.login();
            a.queue("Standard");
            start(a);
            Thread.sleep(100);
            b.queue("Standard");
            start(b);
            // let the match get going, then drop A's socket without resigning
            long end = System.currentTimeMillis() + 20_000;
            while (!b.seen.contains("ActivePlayerSet") && System.currentTimeMillis() < end) Thread.sleep(20);
            a.close();
            await(b, 60_000);
            assertEquals(b.accountId, b.completed.get("winner").asText());
            b.close();
        }
    }

    @Test
    void gameChatReachesBothPlayers() throws Exception {
        MatchSettings s = MatchSettings.fast();
        s.botQueueDelaySeconds = 0;
        try (Harness h = new Harness(s)) {
            Bot a = new Bot(h.port, "Lyra"), b = new Bot(h.port, "Kaito");
            a.login();
            b.login();
            a.queue("Standard");
            start(a);
            Thread.sleep(100);
            b.queue("Standard");
            start(b);
            long end = System.currentTimeMillis() + 20_000;
            while (a.gameId == null && System.currentTimeMillis() < end) Thread.sleep(20);
            a.send("GameChat", Map.of("gameID", a.gameId, "message", "gl hf"), 0);
            end = System.currentTimeMillis() + 10_000;
            while ((b.chat.isEmpty() || a.chat.isEmpty()) && System.currentTimeMillis() < end) Thread.sleep(20);
            assertEquals("gl hf", b.chat.get(0).get("message").asText());
            assertEquals(a.accountId, b.chat.get(0).get("userInfo").get("accountID").asText());
            a.concedeAfterFirstTurnOffer = true;
            await(b, 60_000);
            a.close();
            b.close();
        }
    }
}
