package com.ptcgo.revival.game.match;

import com.ptcgo.revival.Services;
import com.ptcgo.revival.deck.ThemeDecks;
import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.engine.MatchHooks;
import com.ptcgo.revival.game.engine.MatchSettings;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.packet.ClientContext;
import com.ptcgo.revival.packet.PacketHandler;
import com.ptcgo.revival.packet.VersusHooks;
import com.ptcgo.revival.tournament.TournamentMatchRunner;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import static com.ptcgo.revival.game.wire.Msgs.obj;

/**
 * Assembles matchmaking and gameplay and exposes the connection-lifecycle hooks AuthHandler / ClientConnection call.
 * Looked up by the connection's client registry, so {@code Services} needs no new field and two servers in one JVM
 * (tests) stay separate. Create it BEFORE the SocialModule: the manager is the social module's friend-match starter
 * and in-game chat route.
 */
public final class VersusModule {
    private static final Map<ClientRegistry, VersusModule> INSTALLED = new ConcurrentHashMap<>();

    private final MatchManager manager;
    private final List<PacketHandler> handlers;
    private final TournamentMatchRunner tournamentRunner;

    private VersusModule(MatchManager manager) {
        this.manager = manager;
        this.tournamentRunner = new MatchManagerTournamentRunner(manager);
        this.handlers = List.of(new MatchmakingHandler(manager), new GameplayHandler(manager));
    }

    /** Production wiring: settings from the environment, persistence through the repositories, registered card effects. */
    public static VersusModule create(Services services, ThemeDecks themes) {
        return create(services, MatchSettings.fromEnv(), liveEffects(), new VersusHooks(services), BanPolicy.NONE,
                BotDecks.from(services.catalog(), themes), new java.security.SecureRandom());
    }

    public static VersusModule create(Services services, MatchSettings settings, EffectRegistry registry, MatchHooks hooks,
                                      BanPolicy bans, Supplier<JsonNode> botDeck, RandomGenerator rng) {
        MatchManager m = new MatchManager(services, settings, registry, hooks, bans, botDeck, rng);
        VersusModule v = new VersusModule(m);
        INSTALLED.put(services.registry(), v);
        return v;
    }

    /** The effects every live match runs with: the registered card-effect batches, nothing else. */
    static EffectRegistry liveEffects() {
        EffectRegistry r = EffectRegistry.empty();
        com.ptcgo.revival.game.effects.BatchOneAttacks.register(r);
        com.ptcgo.revival.game.effects.BatchTwoAttacks.register(r);
        com.ptcgo.revival.game.effects.GeneratedStandardBatch.register(r);
        com.ptcgo.revival.game.effects.GeneratedStandardBatch2.register(r);
        return r;
    }

    public MatchManager manager() {
        return manager;
    }

    /** The live-bracket seam: TournamentModule.create(services, versus.tournamentRunner(), ...). */
    public TournamentMatchRunner tournamentRunner() {
        return tournamentRunner;
    }

    /** Handler groups to add to the PacketRouter. */
    public List<PacketHandler> handlers() {
        return handlers;
    }

    public static VersusModule of(ClientContext c) {
        return INSTALLED.get(c.services().registry());
    }

    /**
     * Login hook (AuthHandler): PlayerStillInGame drives the client's reconnect (PlayerStillInGameObserver ->
     * ReconnectToGame) when an unfinished session survives for this account, otherwise PlayerNotInGame.
     */
    public static void sendGameStatus(ClientContext c) {
        VersusModule v = of(c);
        var session = v == null || c.account() == null ? null : v.manager.sessionByPlayer(c.account().accountId());
        if (session != null && !session.isOver()) c.send("PlayerStillInGame", obj("gameID", session.gameId), 0);
        else c.send("PlayerNotInGame", obj(), 0);
    }

    /**
     * Disconnect hook (ClientConnection.close, authenticated connections): leaves queues, drops ready-check
     * pairings and detaches from a live game. Runs on its own virtual thread: close() can be entered from a failed
     * write while holding that connection's write lock.
     */
    public static void onDisconnect(ClientContext c) {
        VersusModule v = of(c);
        if (v == null || c.account() == null) return;
        Thread.startVirtualThread(() -> v.manager.clientGone(c));
    }

    /** Synchronous variant of {@link #onDisconnect} (tests, orderly shutdown). */
    public void disconnectNow(ClientContext c) {
        manager.clientGone(c);
    }
}
