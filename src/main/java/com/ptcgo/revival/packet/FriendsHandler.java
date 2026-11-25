package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.db.Account;
import com.ptcgo.revival.db.SocialRepository;
import com.ptcgo.revival.social.PresenceBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Friend roster, invitations, removal and presence. Port of ptcgo/packets/handlers/social.py (friends part).
 *
 * <p>Same as Python: the roster is the account's own rows (invited and confirmed), so an offline invitee does not see
 * the pending invite in the roster (they get a Notification only if online). There is no block list and no
 * FRIEND_MODE / chat-mode / trade-mode enforcement in the Python server (the modes are just fixed login attributes),
 * so there is none here.
 */
public final class FriendsHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(FriendsHandler.class);

    private final SocialRepository repo;
    private final PresenceBroadcaster presence;

    public FriendsHandler(SocialRepository repo, PresenceBroadcaster presence) {
        this.repo = repo;
        this.presence = presence;
    }

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("GetFriendRoster", this::roster);
        m.put("CreateFriendInvitation", this::invite);
        m.put("AcceptFriendInvitation", this::accept);
        m.put("DeclineFriendInvitation", (c, v, rid, f) -> removeMutual(c, text(v, "accountID")));
        m.put("RemoveFriend", this::remove);
        m.put("SetCurrentPresence", (c, v, rid, f) -> {
            String status = v != null && v.hasNonNull("status") ? v.get("status").asText() : "Online";
            presence.broadcast(c, status);
        });
        return m;
    }

    static String text(JsonNode v, String field) {
        return v != null && v.hasNonNull(field) ? v.get(field).asText() : null;
    }

    /** Friend.serialize(): accountID, displayName, status, presence. */
    static Map<String, Object> friend(String accountId, String displayName, int status, String presence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountID", accountId);
        m.put("displayName", displayName);
        m.put("status", status);
        m.put("presence", presence);
        return m;
    }

    private static String presenceOf(ClientContext c, String accountId) {
        return c.services().registry().find(accountId).isPresent() ? "Online" : "Offline";
    }

    // ---- roster ----------------------------------------------------------------------------------

    private void roster(ClientContext c, JsonNode v, int rid, int flags) throws SQLException {
        if (c.account() == null) return;
        List<Map<String, Object>> roster = new ArrayList<>();
        for (SocialRepository.FriendRow r : repo.friends(c.account().accountId())) {
            roster.add(friend(r.friendId(), r.displayName(), r.status(), presenceOf(c, r.friendId())));
        }
        c.send("FriendRoster", Map.of("roster", roster), rid);
    }

    // ---- invitations -----------------------------------------------------------------------------

    private void error(ClientContext c, int rid, String message) {
        c.send("FriendError", Map.of("error", message), rid);
    }

    private Optional<Account> lookup(ClientContext c, String name) throws SQLException {
        if (name == null || name.isBlank()) return Optional.empty();
        Optional<Account> a = c.services().accounts().findByUsername(name);
        if (a.isPresent()) return a;
        Optional<String> id = repo.accountIdByScreenName(name);
        return id.isPresent() ? c.services().accounts().findById(id.get()) : Optional.empty();
    }

    private void invite(ClientContext c, JsonNode v, int rid, int flags) throws SQLException {
        if (c.account() == null) return;
        String targetName = text(v, "displayName");
        Optional<Account> found = lookup(c, targetName);
        if (found.isEmpty()) {
            error(c, rid, "User '" + targetName + "' not found.");
            return;
        }
        Account target = found.get();
        String me = c.account().accountId();
        if (target.accountId().equals(me)) {
            error(c, rid, "You cannot invite yourself.");
            return;
        }
        LOG.info("[TCP] [{}] Player '{}' inviting '{}'.", c.remoteAddress(), c.account().displayName(), targetName);

        Optional<Integer> mine = repo.status(me, target.accountId());
        Optional<Integer> theirs = repo.status(target.accountId(), me);
        // Already friends: re-send the entry instead of downgrading our row to "Invited" (Python overwrites it).
        if (mine.isPresent() && mine.get() == SocialRepository.FRIENDS) {
            sendFriendAdded(c, friend(target.accountId(), target.displayName(), SocialRepository.FRIENDS,
                    presenceOf(c, target.accountId())), rid);
            return;
        }
        // The target already invited us: promote both rows to Friends.
        if (theirs.isPresent() && theirs.get() == SocialRepository.INVITED) {
            repo.befriend(me, target.accountId());
            sendFriendAdded(c, friend(target.accountId(), target.displayName(), SocialRepository.FRIENDS,
                    presenceOf(c, target.accountId())), rid);
            notifyTargetFriendAdded(c, target.accountId());
            return;
        }
        repo.upsert(me, target.accountId(), SocialRepository.INVITED);
        c.services().registry().find(target.accountId()).ifPresent(t -> {
            Map<String, Object> inner = Map.of("friend", Map.of("accountID", me, "displayName", c.account().displayName()));
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("notificationID", UUID.randomUUID().toString());
            notification.put("payload", json(Map.of("name", "FriendInvitation", "value", inner)));
            notification.put("notificationType", 0);
            t.send("Notification", notification, 0);
        });
        sendFriendAdded(c, friend(target.accountId(), target.displayName(), SocialRepository.INVITED, "Offline"), rid);
    }

    private static String json(Object o) {
        try {
            return com.ptcgo.revival.net.WargCodec.JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sendFriendAdded(ClientContext to, Map<String, Object> friend, int rid) {
        to.send("FriendAdded", Map.of("friend", friend), rid);
    }

    /** If the target is online, push the sender to them as a confirmed friend. */
    private void notifyTargetFriendAdded(ClientContext c, String targetId) {
        c.services().registry().find(targetId).ifPresent(t ->
                sendFriendAdded(t, friend(c.account().accountId(), c.account().displayName(), SocialRepository.FRIENDS, "Online"), 0));
    }

    private void accept(ClientContext c, JsonNode v, int rid, int flags) throws SQLException {
        if (c.account() == null) return;
        String targetId = text(v, "accountID");
        String me = c.account().accountId();
        // Python accepts without checking that an invitation exists, which lets anyone force a friendship on an
        // arbitrary account id. Require a pending (or existing) row from the other side.
        Optional<Integer> theirs = targetId == null ? Optional.empty() : repo.status(targetId, me);
        if (theirs.isEmpty() || (theirs.get() != SocialRepository.INVITED && theirs.get() != SocialRepository.FRIENDS)) {
            error(c, rid, "No pending friend invitation from that user.");
            return;
        }
        LOG.info("[TCP] [{}] Player '{}' accepting invitation from '{}'.", c.remoteAddress(), c.account().displayName(), targetId);
        repo.befriend(me, targetId);
        String name = c.services().accounts().findById(targetId).map(Account::displayName).orElse("Unknown");
        notifyTargetFriendAdded(c, targetId);
        sendFriendAdded(c, friend(targetId, name, SocialRepository.FRIENDS, presenceOf(c, targetId)), rid);
    }

    // ---- removal ---------------------------------------------------------------------------------

    /** Deletes both rows and tells the other side if online (no reply to the requester, as in Python). */
    private void removeMutual(ClientContext c, String targetId) throws SQLException {
        if (c.account() == null || targetId == null) return;
        String me = c.account().accountId();
        repo.removeBoth(me, targetId);
        c.services().registry().find(targetId).ifPresent(t -> t.send("FriendRemoved", Map.of("accountID", me), 0));
    }

    private void remove(ClientContext c, JsonNode v, int rid, int flags) throws SQLException {
        String targetId = text(v, "accountID");
        removeMutual(c, targetId);
        if (c.account() != null) c.send("FriendRemoved", Map.of("accountID", targetId == null ? "" : targetId), rid);
    }
}
