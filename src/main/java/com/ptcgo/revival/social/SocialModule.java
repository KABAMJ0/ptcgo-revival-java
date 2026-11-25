package com.ptcgo.revival.social;

import com.ptcgo.revival.Services;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.SocialRepository;
import com.ptcgo.revival.db.TradeRepository;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.packet.ChallengeHandler;
import com.ptcgo.revival.packet.ChatHandler;
import com.ptcgo.revival.packet.ClientContext;
import com.ptcgo.revival.packet.FriendsHandler;
import com.ptcgo.revival.packet.PacketHandler;
import com.ptcgo.revival.packet.TradeHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assembles friends, chat, challenges and trade, and exposes the two connection-lifecycle hooks
 * ({@link #onLogin}, {@link #onDisconnect}) that AuthHandler / ClientConnection call. Modules are looked up by the
 * client registry of the connection, so no change to {@code Services} is needed and two servers in one JVM
 * (tests) stay separate.
 */
public final class SocialModule {
    private static final Map<ClientRegistry, SocialModule> INSTALLED = new ConcurrentHashMap<>();

    private final PresenceBroadcaster presence;
    private final ChatRooms rooms = new ChatRooms();
    private final ChallengeHandler challenges;
    private final List<PacketHandler> handlers;

    private SocialModule(Database db, FriendMatchStarter starter, GameChatRouter gameChat) {
        SocialRepository social = new SocialRepository(db);
        this.presence = new PresenceBroadcaster(social);
        this.challenges = new ChallengeHandler(starter);
        this.handlers = List.of(new FriendsHandler(social, presence), new ChatHandler(rooms, gameChat), challenges,
                new TradeHandler(new TradeRepository(db)));
    }

    /**
     * Builds the module and installs it for the services' client registry (the hooks find it there).
     * Needs the database tables from {@code Database.migrate()}.
     */
    public static SocialModule create(Services services, FriendMatchStarter starter, GameChatRouter gameChat) {
        SocialModule m = new SocialModule(services.players().database(), starter, gameChat);
        INSTALLED.put(services.registry(), m);
        return m;
    }

    /** Handler groups to add to the {@code PacketRouter}. */
    public List<PacketHandler> handlers() {
        return handlers;
    }

    public PresenceBroadcaster presence() {
        return presence;
    }

    public ChatRooms rooms() {
        return rooms;
    }

    public ChallengeHandler challenges() {
        return challenges;
    }

    /** The module installed for this connection's server, or null. */
    public static SocialModule of(ClientContext c) {
        return INSTALLED.get(c.services().registry());
    }

    /** Hook: after {@code ClientContext.authenticated(...)} in AuthHandler.success. No-op if no module is installed. */
    public static void onLogin(ClientContext c) {
        SocialModule m = of(c);
        if (m != null) m.presence.online(c);
    }

    /**
     * Hook: in ClientConnection.close() after the registry unregister, for authenticated connections. The work runs on a
     * virtual thread: close() can be entered from a failed write while holding that connection's write lock, and
     * sending to the friends from there could deadlock against a friend's concurrent close.
     */
    public static void onDisconnect(ClientContext c) {
        SocialModule m = of(c);
        if (m == null || c.account() == null) return;
        Thread.startVirtualThread(() -> m.disconnectNow(c));
    }

    /** Synchronous variant of {@link #onDisconnect} (tests, orderly shutdown). */
    public void disconnectNow(ClientContext c) {
        if (c.account() == null) return;
        rooms.removeEverywhere(c);
        challenges.clientGone(c);
        presence.offline(c);
    }
}
