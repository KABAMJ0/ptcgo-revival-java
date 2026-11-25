package com.ptcgo.revival.social;

import com.ptcgo.revival.db.SocialRepository;
import com.ptcgo.revival.packet.ClientContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pushes {@code FriendPresence} to the online friends of a client. Mirrors SocialHandler.broadcast_presence:
 * the audience is every row of the account's own friend list (invited rows included), and the presence string
 * is sent verbatim.
 */
public final class PresenceBroadcaster {
    private static final Logger LOG = LoggerFactory.getLogger(PresenceBroadcaster.class);

    private final SocialRepository repo;

    public PresenceBroadcaster(SocialRepository repo) {
        this.repo = repo;
    }

    /** Call after a successful login (once {@code ClientContext.authenticated} has run). */
    public void online(ClientContext c) {
        broadcast(c, "Online");
    }

    /** Call when an authenticated connection closes. Never throws. */
    public void offline(ClientContext c) {
        broadcast(c, "Offline");
    }

    public void broadcast(ClientContext c, String presence) {
        if (c.account() == null) return;
        String me = c.account().accountId();
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("accountID", me);
        packet.put("presence", presence);
        try {
            for (SocialRepository.FriendRow f : repo.friends(me)) {
                c.services().registry().find(f.friendId()).ifPresent(t -> t.send("FriendPresence", packet, 0));
            }
        } catch (SQLException | RuntimeException e) {
            LOG.warn("[Social] Presence broadcast ({}) failed for {}: {}", presence, me, e.toString());
        }
    }
}
