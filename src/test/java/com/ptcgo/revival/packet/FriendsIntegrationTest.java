package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.db.SocialRepository;
import com.ptcgo.revival.social.FriendMatchStarter;
import com.ptcgo.revival.social.GameChatRouter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FriendsIntegrationTest {
    private static SocialTestSupport env() throws Exception {
        return new SocialTestSupport(FriendMatchStarter.UNAVAILABLE, GameChatRouter.NONE);
    }

    @Test
    void inviteAcceptRosterAndPresence() throws Exception {
        try (SocialTestSupport env = env();
             SocialTestSupport.Client ash = env.login("Ash");
             SocialTestSupport.Client misty = env.login("Misty")) {
            // Ash invites Misty (online): Ash sees her as Invited/Offline on the request id, Misty gets a Notification.
            ash.send("CreateFriendInvitation", Map.of("displayName", "misty"), 10);
            JsonNode added = ash.expect("FriendAdded");
            assertEquals(10, ash.lastRequestId);
            assertEquals(misty.accountId, added.get("friend").get("accountID").asText());
            assertEquals(SocialRepository.INVITED, added.get("friend").get("status").asInt());
            assertEquals("Offline", added.get("friend").get("presence").asText());

            JsonNode note = misty.expect("Notification");
            JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(note.get("payload").asText());
            assertEquals("FriendInvitation", payload.get("name").asText());
            assertEquals(ash.accountId, payload.get("value").get("friend").get("accountID").asText());
            assertEquals("Ash", payload.get("value").get("friend").get("displayName").asText());
            assertEquals(0, note.get("notificationType").asInt());

            // The invitee's roster has no row yet (Python stores invites on the sender's side only).
            misty.send("GetFriendRoster", Map.of(), 11);
            assertEquals(0, misty.expect("FriendRoster").get("roster").size());

            // Misty accepts: Ash is pushed the new friend (rid 0), Misty gets hers on the request id.
            misty.send("AcceptFriendInvitation", Map.of("accountID", ash.accountId), 12);
            JsonNode toAsh = ash.expect("FriendAdded");
            assertEquals(0, ash.lastRequestId);
            assertEquals(misty.accountId, toAsh.get("friend").get("accountID").asText());
            assertEquals(SocialRepository.FRIENDS, toAsh.get("friend").get("status").asInt());
            assertEquals("Online", toAsh.get("friend").get("presence").asText());
            JsonNode toMisty = misty.expect("FriendAdded");
            assertEquals(12, misty.lastRequestId);
            assertEquals(ash.accountId, toMisty.get("friend").get("accountID").asText());
            assertEquals("Ash", toMisty.get("friend").get("displayName").asText());

            // Roster: exactly the FriendsList.serialize shape, presence computed from who is online.
            ash.send("GetFriendRoster", Map.of(), 13);
            JsonNode roster = ash.expect("FriendRoster").get("roster");
            assertEquals(1, roster.size());
            JsonNode e = roster.get(0);
            assertEquals(4, e.size());
            assertEquals(misty.accountId, e.get("accountID").asText());
            assertEquals("Misty", e.get("displayName").asText());
            assertEquals(4, e.get("status").asInt());
            assertEquals("Online", e.get("presence").asText());

            // SetCurrentPresence is relayed verbatim to friends.
            ash.send("SetCurrentPresence", Map.of("status", "Away"), 14);
            JsonNode p = misty.expect("FriendPresence");
            assertEquals(ash.accountId, p.get("accountID").asText());
            assertEquals("Away", p.get("presence").asText());

            // The login / disconnect hooks.
            ash.send("HookLogin", Map.of(), 15);
            assertEquals("Online", misty.expect("FriendPresence").get("presence").asText());
            ash.send("HookDisconnectNow", Map.of(), 16);
            assertEquals("Offline", misty.expect("FriendPresence").get("presence").asText());
            ash.send("HookDisconnectAsync", Map.of(), 17);
            assertEquals("Offline", misty.expect("FriendPresence").get("presence").asText());
        }
    }

    @Test
    void mutualInvitePromotesAndRemoveNotifiesBothSides() throws Exception {
        try (SocialTestSupport env = env();
             SocialTestSupport.Client ash = env.login("Ash");
             SocialTestSupport.Client misty = env.login("Misty")) {
            ash.send("CreateFriendInvitation", Map.of("displayName", "Misty"), 1);
            ash.expect("FriendAdded");
            misty.expect("Notification");
            // Misty invites back: instantly friends.
            misty.send("CreateFriendInvitation", Map.of("displayName", "Ash"), 2);
            JsonNode mine = misty.expect("FriendAdded");
            assertEquals(2, misty.lastRequestId);
            assertEquals(SocialRepository.FRIENDS, mine.get("friend").get("status").asInt());
            JsonNode theirs = ash.expect("FriendAdded");
            assertEquals(SocialRepository.FRIENDS, theirs.get("friend").get("status").asInt());
            assertEquals(misty.accountId, theirs.get("friend").get("accountID").asText());

            // Inviting an existing friend does not downgrade the row.
            ash.send("CreateFriendInvitation", Map.of("displayName", "Misty"), 3);
            assertEquals(SocialRepository.FRIENDS, ash.expect("FriendAdded").get("friend").get("status").asInt());

            ash.send("RemoveFriend", Map.of("accountID", misty.accountId), 4);
            JsonNode toMisty = misty.expect("FriendRemoved");
            assertEquals(0, misty.lastRequestId);
            assertEquals(ash.accountId, toMisty.get("accountID").asText());
            JsonNode toAsh = ash.expect("FriendRemoved");
            assertEquals(4, ash.lastRequestId);
            assertEquals(misty.accountId, toAsh.get("accountID").asText());
            ash.send("GetFriendRoster", Map.of(), 5);
            assertEquals(0, ash.expect("FriendRoster").get("roster").size());
            assertTrue(misty.quiet());
        }
    }

    @Test
    void declineRemovesSilentlyForTheDecliner() throws Exception {
        try (SocialTestSupport env = env();
             SocialTestSupport.Client ash = env.login("Ash");
             SocialTestSupport.Client misty = env.login("Misty")) {
            ash.send("CreateFriendInvitation", Map.of("displayName", "Misty"), 1);
            ash.expect("FriendAdded");
            misty.expect("Notification");
            misty.send("DeclineFriendInvitation", Map.of("accountID", ash.accountId), 2);
            assertEquals(misty.accountId, ash.expect("FriendRemoved").get("accountID").asText());
            assertTrue(misty.quiet(), "Python sends the decliner no reply");
            ash.send("GetFriendRoster", Map.of(), 3);
            assertEquals(0, ash.expect("FriendRoster").get("roster").size());
        }
    }

    @Test
    void errorsAndSafeguards() throws Exception {
        try (SocialTestSupport env = env();
             SocialTestSupport.Client ash = env.login("Ash");
             SocialTestSupport.Client misty = env.login("Misty")) {
            ash.send("CreateFriendInvitation", Map.of("displayName", "Nobody"), 1);
            assertEquals("User 'Nobody' not found.", ash.expect("FriendError").get("error").asText());
            assertEquals(1, ash.lastRequestId);
            ash.send("CreateFriendInvitation", Map.of("displayName", "ASH"), 2);
            assertEquals("You cannot invite yourself.", ash.expect("FriendError").get("error").asText());
            // Accepting an invitation that was never sent must not create a friendship.
            ash.send("AcceptFriendInvitation", Map.of("accountID", misty.accountId), 3);
            ash.expect("FriendError");
            misty.send("GetFriendRoster", Map.of(), 4);
            assertEquals(0, misty.expect("FriendRoster").get("roster").size());
            // Screen-name lookup works too.
            env.services.players().database(); // schema present
            try (var c = env.services.players().database().connect(); var st = c.createStatement()) {
                st.executeUpdate("UPDATE accounts SET screen_name = 'Cerulean Gym' WHERE username = 'Misty'");
            }
            ash.send("CreateFriendInvitation", Map.of("displayName", "cerulean gym"), 5);
            JsonNode f = ash.expect("FriendAdded").get("friend");
            assertEquals(misty.accountId, f.get("accountID").asText());
            assertEquals("Cerulean Gym", f.get("displayName").asText());
        }
    }

    @Test
    void rosterFromPythonRowsIncludesOfflineFriends() throws Exception {
        try (SocialTestSupport env = env();
             SocialTestSupport.Client ash = env.login("Ash")) {
            var acc = env.services.accounts().create("Brock", "pw", false).orElseThrow();
            var repo = new SocialRepository(env.services.players().database());
            repo.upsert(ash.accountId, acc.accountId(), SocialRepository.FRIENDS);
            ash.send("GetFriendRoster", Map.of(), 1);
            JsonNode e = ash.expect("FriendRoster").get("roster").get(0);
            assertEquals("Brock", e.get("displayName").asText());
            assertEquals("Offline", e.get("presence").asText());
        }
    }

    /** The hooks wired into AuthHandler and ClientConnection (not the test stand-ins). */
    @Test
    void realLoginAndSocketCloseBroadcastPresence() throws Exception {
        try (SocialTestSupport env = env();
             SocialTestSupport.Client ash = env.login("Ash")) {
            var misty = env.services.accounts().create("Misty", "pw", false).orElseThrow();
            var repo = new SocialRepository(env.services.players().database());
            repo.upsert(ash.accountId, misty.accountId(), SocialRepository.FRIENDS);
            repo.upsert(misty.accountId(), ash.accountId, SocialRepository.FRIENDS);

            SocialTestSupport.Client m = env.login("Misty");
            JsonNode online = ash.expect("FriendPresence");
            assertEquals(misty.accountId(), online.get("accountID").asText());
            assertEquals("Online", online.get("presence").asText());

            m.close(); // the server notices the closed socket and runs ClientConnection.close()
            JsonNode offline = ash.expect("FriendPresence");
            assertEquals(misty.accountId(), offline.get("accountID").asText());
            assertEquals("Offline", offline.get("presence").asText());
        }
    }
}
