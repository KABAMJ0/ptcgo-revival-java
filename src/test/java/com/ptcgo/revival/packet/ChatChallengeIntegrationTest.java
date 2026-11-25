package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.social.FriendMatchStarter;
import com.ptcgo.revival.social.GameChatRouter;
import com.ptcgo.revival.packet.SocialTestSupport.Client;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ChatChallengeIntegrationTest {
    /** Records what the (unported) match engine would be asked to do. */
    static final class RecordingStarter implements FriendMatchStarter {
        final List<String> started = new CopyOnWriteArrayList<>();
        volatile String busy;

        @Override
        public boolean isInMatch(String accountId) {
            return accountId.equals(busy);
        }

        @Override
        public void start(com.ptcgo.revival.packet.ClientContext challenger, JsonNode cd,
                          com.ptcgo.revival.packet.ClientContext accepter, JsonNode ad, JsonNode options) {
            started.add(challenger.account().username() + " vs " + accepter.account().username()
                    + " decks=" + cd + "/" + ad);
        }
    }

    @Test
    void privateChatEchoesAndRoutes() throws Exception {
        try (SocialTestSupport env = new SocialTestSupport(FriendMatchStarter.UNAVAILABLE, GameChatRouter.NONE);
             Client ash = env.login("Ash"); Client misty = env.login("Misty")) {
            ash.send("PrivateChat", Map.of("accountID", misty.accountId, "message", "hi", "purpose", "friend"), 5);
            JsonNode echo = ash.expect("NotifyPrivateChat");
            assertEquals(0, ash.lastRequestId);
            JsonNode got = misty.expect("NotifyPrivateChat");
            assertEquals(echo, got);
            assertEquals("hi", got.get("message").asText());
            assertEquals("friend", got.get("purpose").asText());
            assertEquals(ash.accountId, got.get("userInfo").get("accountID").asText());
            assertEquals("Misty", got.get("toUserInfo").get("displayName").asText());
            assertTrue(got.get("targetWasOnline").asBoolean());

            ash.send("PrivateChat", Map.of("accountID", "00000000-no-such", "message", "anyone?"), 6);
            JsonNode off = ash.expect("NotifyPrivateChat");
            assertEquals("Offline Player", off.get("toUserInfo").get("displayName").asText());
            assertFalse(off.get("targetWasOnline").asBoolean());
            assertEquals("", off.get("purpose").asText());
            assertTrue(misty.quiet());
        }
    }

    @Test
    void lobbyRoomJoinChatLeave() throws Exception {
        try (SocialTestSupport env = new SocialTestSupport(FriendMatchStarter.UNAVAILABLE, GameChatRouter.NONE);
             Client ash = env.login("Ash"); Client misty = env.login("Misty")) {
            ash.send("PublicRooms", Map.of(), 1);
            JsonNode rooms = ash.expect("AvailableRooms").get("rooms");
            assertEquals("Lobby_Spirit_1", rooms.get(0).get("roomID").asText());
            assertEquals(0, rooms.get(0).get("roomType").asInt());

            ash.send("JoinRoom", Map.of("roomID", "nope"), 2); // unknown room: ignored
            assertTrue(ash.quiet());

            ash.send("Chat", Map.of("roomID", "Lobby_Spirit_1", "message", "not joined"), 3);
            assertTrue(ash.quiet());

            ash.send("JoinRoom", Map.of("roomID", "Lobby_Spirit_1"), 4);
            JsonNode c1 = ash.expect("ChatConnected");
            assertEquals(4, ash.lastRequestId);
            assertEquals(1, c1.get("members").size()); // the joiner is included, the client drops it
            assertEquals(0, c1.get("messageHistory").size());

            misty.send("JoinRoom", Map.of("roomID", "Lobby_Spirit_1"), 5);
            JsonNode join = ash.expect("NotifyJoin");
            assertEquals(misty.accountId, join.get("userInfo").get("accountID").asText());
            assertEquals(2, misty.expect("ChatConnected").get("members").size());

            ash.send("Chat", Map.of("roomID", "Lobby_Spirit_1", "message", "hello lobby"), 6);
            for (Client c : List.of(ash, misty)) { // sender gets it too (no echo path)
                JsonNode n = c.expect("NotifyChat");
                assertEquals("hello lobby", n.get("message").asText());
                assertEquals("Ash", n.get("userInfo").get("displayName").asText());
            }

            misty.send("LeaveRoom", Map.of("roomID", "Lobby_Spirit_1"), 7);
            assertEquals("Lobby_Spirit_1", misty.expect("ChatDisconnected").get("roomID").asText());
            assertEquals(7, misty.lastRequestId);
            JsonNode leave = ash.expect("NotifyLeave");
            assertEquals(misty.accountId, leave.get("accountID").asText());
            ash.send("Chat", Map.of("roomID", "Lobby_Spirit_1", "message", "alone"), 8);
            ash.expect("NotifyChat");
            assertTrue(misty.quiet());
        }
    }

    @Test
    void gameChatGoesThroughTheRouterSeam() throws Exception {
        GameChatRouter[] router = new GameChatRouter[1];
        try (SocialTestSupport env = new SocialTestSupport(FriendMatchStarter.UNAVAILABLE,
                (sender, gameId) -> router[0].recipients(sender, gameId));
             Client ash = env.login("Ash"); Client misty = env.login("Misty")) {
            router[0] = (sender, gameId) -> List.of();
            ash.send("GameChat", Map.of("gameID", "g1", "message", "gl"), 1);
            assertTrue(ash.quiet() && misty.quiet(), "no session: dropped");

            router[0] = (sender, gameId) -> {
                var reg = env.services.registry();
                List<ClientContext> l = new ArrayList<>();
                if (gameId.equals("g1")) {
                    reg.find(ash.accountId).ifPresent(l::add);
                    reg.find(misty.accountId).ifPresent(l::add);
                }
                return l;
            };
            ash.send("GameChat", Map.of("gameID", "g1", "message", "gl hf"), 2);
            for (Client c : List.of(ash, misty)) {
                JsonNode n = c.expect("NotifyGameChat");
                assertEquals("g1", n.get("gameID").asText());
                assertEquals("gl hf", n.get("message").asText());
                assertEquals(ash.accountId, n.get("userInfo").get("accountID").asText());
            }
            ash.send("GameChat", Map.of("gameID", "other", "message", "x"), 3);
            assertTrue(ash.quiet() && misty.quiet());
        }
    }

    @Test
    void challengeLifecycle() throws Exception {
        RecordingStarter starter = new RecordingStarter();
        try (SocialTestSupport env = new SocialTestSupport(starter, GameChatRouter.NONE);
             Client ash = env.login("Ash"); Client misty = env.login("Misty")) {
            // The RPC reply is a bare {} on the request id; then the failure message.
            ash.send("RequestMatchWithSpecificClient", Map.of("opponentID", "ghost", "deck", Map.of()), 1);
            JsonNode bare = ash.next();
            assertEquals(1, ash.lastRequestId);
            assertTrue(bare.isObject() && bare.size() == 0);
            assertEquals("Your opponent is no longer online.",
                    ash.expect("MatchRequestWithSpecificClientFailed").get("failureType").get("id").asText());

            starter.busy = misty.accountId;
            ash.send("RequestMatchWithSpecificClient", Map.of("opponentID", misty.accountId), 2);
            ash.next();
            assertEquals("Your opponent is already in a match.",
                    ash.expect("MatchRequestWithSpecificClientFailed").get("failureType").get("id").asText());
            starter.busy = null;

            // Request -> reject
            ash.send("RequestMatchWithSpecificClient", Map.of("opponentID", misty.accountId,
                    "deck", Map.of("deckName", "A"), "matchOptions", Map.of("k", 1)), 3);
            ash.next();
            JsonNode sent = ash.expect("MatchRequestSent");
            String path = sent.get("path").asText();
            assertTrue(path.startsWith("challenge_"));
            assertEquals(misty.accountId, sent.get("opponentID").asText());
            JsonNode req = misty.expect("MatchRequested");
            assertEquals(path, req.get("path").asText());
            assertEquals(ash.accountId, req.get("opponentID").asText());
            assertEquals(1, req.get("matchOptions").get("k").asInt());

            misty.send("RejectMatchWithSpecificClient", Map.of("path", path), 4);
            misty.next();
            JsonNode rej = ash.expect("MatchRequestRejected");
            assertEquals("Rejected", rej.get("failureType").asText());
            assertEquals(0, env.social.challenges().pendingCount());

            // Request -> cancel by the challenger: both parties are told
            ash.send("RequestMatchWithSpecificClient", Map.of("opponentID", misty.accountId), 5);
            ash.next();
            String p2 = ash.expect("MatchRequestSent").get("path").asText();
            misty.expect("MatchRequested");
            ash.send("CancelMatchRequestWithSpecificClient", Map.of("path", p2), 6);
            ash.next();
            assertEquals(p2, ash.expect("MatchRequestCancelled").get("path").asText());
            assertEquals(p2, misty.expect("MatchRequestCancelled").get("path").asText());

            // A second request replaces the first (cancel goes to the other party only)
            ash.send("RequestMatchWithSpecificClient", Map.of("opponentID", misty.accountId), 7);
            ash.next();
            String p3 = ash.expect("MatchRequestSent").get("path").asText();
            misty.expect("MatchRequested");
            ash.send("RequestMatchWithSpecificClient", Map.of("opponentID", misty.accountId), 8);
            assertEquals(p3, misty.expect("MatchRequestCancelled").get("path").asText());
            ash.next();
            String p4 = ash.expect("MatchRequestSent").get("path").asText();
            misty.expect("MatchRequested");
            assertNotEquals(p3, p4);

            // Accept by the wrong party / unknown path fails; the right party starts the match via the seam
            ash.send("AcceptMatchWithSpecificClient", Map.of("path", p4), 9);
            ash.next();
            assertEquals("This challenge is no longer available.",
                    ash.expect("MatchRequestWithSpecificClientFailed").get("failureType").get("id").asText());
            misty.send("AcceptMatchWithSpecificClient", Map.of("path", p4, "deck", Map.of("deckName", "B")), 10);
            misty.next();
            assertEquals(ash.accountId, misty.expect("MatchRequestSent").get("opponentID").asText());
            for (int i = 0; i < 50 && starter.started.isEmpty(); i++) Thread.sleep(20);
            assertEquals(1, starter.started.size());
            assertTrue(starter.started.get(0).startsWith("Ash vs Misty"), starter.started.get(0));

            // Disconnect cleanup
            ash.send("RequestMatchWithSpecificClient", Map.of("opponentID", misty.accountId), 11);
            ash.next();
            String p5 = ash.expect("MatchRequestSent").get("path").asText();
            misty.expect("MatchRequested");
            ash.send("HookDisconnectNow", Map.of(), 12);
            assertEquals(p5, misty.expect("MatchRequestCancelled").get("path").asText());
            assertEquals(0, env.social.challenges().pendingCount());
        }
    }

    @Test
    void defaultStarterReportsUnavailable() throws Exception {
        try (SocialTestSupport env = new SocialTestSupport(FriendMatchStarter.UNAVAILABLE, GameChatRouter.NONE);
             Client ash = env.login("Ash"); Client misty = env.login("Misty")) {
            ash.send("RequestMatchWithSpecificClient", Map.of("opponentID", misty.accountId), 1);
            ash.next();
            String path = ash.expect("MatchRequestSent").get("path").asText();
            misty.expect("MatchRequested");
            misty.send("AcceptMatchWithSpecificClient", Map.of("path", path), 2);
            misty.next();
            misty.expect("MatchRequestSent");
            misty.expect("MatchRequestWithSpecificClientFailed");
            ash.expect("MatchRequestWithSpecificClientFailed");
        }
    }
}
