package com.ptcgo.revival.game.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.game.ai.AiPolicy;
import com.ptcgo.revival.game.effects.ConditionState;
import com.ptcgo.revival.game.effects.DamageModifier;
import com.ptcgo.revival.game.effects.EffectContext;
import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.effects.SessionOps;
import com.ptcgo.revival.game.effects.TurnStateView;
import com.ptcgo.revival.game.engine.LegalActions.LegalAction;
import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;
import com.ptcgo.revival.game.model.Entity;
import com.ptcgo.revival.game.model.PlayArea;
import com.ptcgo.revival.game.wire.Attr;
import com.ptcgo.revival.game.wire.GameSequence;
import com.ptcgo.revival.game.wire.Msgs;
import com.ptcgo.revival.game.wire.Msgs.NestedSequence;
import com.ptcgo.revival.game.wire.SelectionKind;
import com.ptcgo.revival.packet.ClientContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

import static com.ptcgo.revival.game.wire.GameConstants.*;
import static com.ptcgo.revival.game.wire.Msgs.obj;

/**
 * One running match (game_session.py). The gameplay sequence runs on its own virtual thread and blocks on the
 * players' replies; network threads only complete futures ({@link #receivePlayerAction}). Every game message is
 * sent as a COMPLETE Start/Stop bracket under {@link #wire} so brackets from the two setup threads never
 * interleave. Card effects are not part of the engine: attacks deal printed damage and Trainers do nothing unless an
 * {@link EffectRegistry} entry says otherwise.
 */
public final class GameSession implements SessionOps {
    private static final Logger LOG = LoggerFactory.getLogger(GameSession.class);

    /** A reply to an offer: the client's {@code selection} and echoed {@code counter}. */
    public record Reply(JsonNode selection, Integer counter, boolean timedOut) {
        public boolean isNull() {
            return selection == null || selection.isNull() || selection.isMissingNode();
        }
    }

    public final String gameId;
    public final boolean solo;
    public final String queueName;
    final LinkedHashMap<String, GamePlayer> players = new LinkedHashMap<>();
    final Board board;
    final TurnState turn = new TurnState();
    private final Catalog catalog;
    private final EffectRegistry registry;
    private final MatchSettings settings;
    private final MatchHooks hooks;
    private final RandomGenerator rng;
    private final Consumer<String> onClose;
    private final AiPolicy aiPolicy;
    private final ConditionState conditionState = new ConditionState();

    /** Serializes outbound brackets/prompts so Start..Stop runs stay contiguous. */
    private final ReentrantLock wire = new ReentrantLock();
    private volatile GamePhase phase = GamePhase.INIT;
    private String coinFlipCallerId, coinFlipWinnerId, firstPlayerId;
    private final Map<String, Integer> selectionCounters = new HashMap<>();
    final Map<String, Map<String, Integer>> stats = new HashMap<>();
    /** player -> archetype guid -> [total damage, name loc key] for the EOG MVP pick. */
    private final Map<String, Map<String, Object[]>> mvpDamage = new HashMap<>();
    private final long matchStartedAt;
    private volatile Thread gameplayThread;
    private volatile boolean closed;
    private record GameResult(String winner, String loser, String reason) {}
    private volatile GameResult result;
    /** The turn number the last end-of-game ran on (0 = decided before the first turn). */
    private int turnsAtEnd;

    // ---- readiness / reconnect state (guarded by stateLock unless volatile) ---------------------
    private final Object stateLock = new Object();
    private final Set<String> readyPlayers = new HashSet<>();
    private final Set<String> reconnecting = new HashSet<>();
    private final Set<String> disconnected = new HashSet<>();
    private final Set<String> offersToRestore = new HashSet<>();
    private final Set<String> restoringOffers = new HashSet<>();
    private final Set<String> resyncPlayers = new HashSet<>();
    private final Set<String> resyncReady = new HashSet<>();
    private final Map<String, String> pausePrompts = new HashMap<>();
    private final Map<String, Thread> graceTimers = new HashMap<>();
    private volatile boolean stateDispatched;
    private volatile boolean resumed = true;
    private int resyncEpoch;

    // ---- reconnect checkpoints: SGS snapshots are only taken between state units or at prompts ----
    private final Object checkpointLock = new Object();
    private int unitThreads, promptThreads;

    private static final class UnitState {
        int depth;
        boolean prompting;
    }

    private final ThreadLocal<UnitState> unitState = ThreadLocal.withInitial(UnitState::new);

    public GameSession(String gameId, boolean solo, String queueName, List<GamePlayer> seats, Catalog catalog,
                       EffectRegistry registry, MatchSettings settings, MatchHooks hooks, RandomGenerator rng,
                       Consumer<String> onClose) {
        this.gameId = gameId;
        this.solo = solo;
        this.queueName = queueName;
        this.catalog = catalog;
        this.registry = registry;
        this.settings = settings;
        this.hooks = hooks;
        this.rng = rng;
        this.onClose = onClose;
        this.aiPolicy = new AiPolicy();
        this.matchStartedAt = System.currentTimeMillis();
        for (GamePlayer p : seats) {
            players.put(p.accountId, p);
            selectionCounters.put(p.accountId, 2); // the pregame flip uses counters 1-2
            stats.put(p.accountId, new HashMap<>());
            mvpDamage.put(p.accountId, new HashMap<>());
        }
        this.board = new Board(gameId, new ArrayList<>(players.keySet()), rng);
        for (GamePlayer p : seats) {
            board.populateDeck(p.accountId, p.deckData, catalog);
            var pe = board.playerEntity(p.accountId);
            if (pe != null) pe.set(Attr.NAME, p.screenName());
        }
    }

    // ---- accessors (SessionOps and tests) ---------------------------------------------------------

    @Override
    public String gameId() {
        return gameId;
    }

    @Override
    public Board board() {
        return board;
    }

    @Override
    public TurnStateView turn() {
        return turn;
    }

    public TurnState turnState() {
        return turn;
    }

    @Override
    public RandomGenerator rng() {
        return rng;
    }

    @Override
    public EffectRegistry registry() {
        return registry;
    }

    @Override
    public ConditionState conditions() {
        return conditionState;
    }

    public GamePhase phase() {
        return phase;
    }

    public Map<String, GamePlayer> players() {
        return players;
    }

    public MatchSettings settings() {
        return settings;
    }

    public String winnerId() {
        return result == null ? null : result.winner();
    }

    public String resultReason() {
        return result == null ? null : result.reason();
    }

    public Map<String, Integer> statsOf(String playerId) {
        return stats.get(playerId);
    }

    public boolean isOver() {
        return phase == GamePhase.GAME_OVER;
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private void pause(long ms) {
        if (settings.choreographyPauses && ms > 0) sleep(ms);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameOverException();
        }
    }

    private void aiPause(long extraMs) {
        pause(settings.aiActionDelayMs + extraMs);
    }

    @Override
    public void statAdd(String playerId, String key, int amount) {
        stats.computeIfAbsent(playerId, k -> new HashMap<>()).merge(key, amount, Integer::sum);
    }

    @Override
    public void statMax(String playerId, String key, int value) {
        stats.computeIfAbsent(playerId, k -> new HashMap<>()).merge(key, value, Math::max);
    }

    @Override
    public void creditCardDamage(String playerId, CardEntity attacker, int amount) {
        if (attacker == null || amount <= 0) return;
        Object name = attacker.get(Attr.NAME);
        String nameId = name instanceof JsonNode j ? j.path("id").asText("") : name instanceof Map<?, ?> m
                ? String.valueOf(m.get("id")) : String.valueOf(name);
        Object[] entry = mvpDamage.computeIfAbsent(playerId, k -> new HashMap<>())
                .computeIfAbsent(attacker.archetypeId, k -> new Object[] {0, nameId});
        entry[0] = (Integer) entry[0] + amount;
    }

    @Override
    public void recordDamage(String targetId, int dealt, boolean byAttack) {
        turn.damageTaken.merge(targetId, dealt, Integer::sum);
        if (byAttack) turn.attackDamageTaken.merge(targetId, dealt, Integer::sum);
    }

    @Override
    public void markHealed(String entityId) {
        turn.healedEntities.add(entityId);
    }

    @Override
    public int maxHp(CardEntity pokemon) {
        return pokemon.maxHp();
    }

    String opponentId(String playerId) {
        return board.opponentOf(playerId);
    }

    /** Player ids with the first player first, so draws/prizes animate in turn order. */
    List<String> turnOrder() {
        List<String> ids = new ArrayList<>(players.keySet());
        if (firstPlayerId != null && ids.remove(firstPlayerId)) ids.add(0, firstPlayerId);
        return ids;
    }

    private int nextCounter(String playerId) {
        return selectionCounters.merge(playerId, 1, Integer::sum);
    }

    private List<GamePlayer> allPlayers() {
        return new ArrayList<>(players.values());
    }

    private Map<String, NetworkPlayer> networkPlayers() {
        Map<String, NetworkPlayer> m = new LinkedHashMap<>();
        for (var e : players.entrySet()) if (e.getValue() instanceof NetworkPlayer n) m.put(e.getKey(), n);
        return m;
    }

    /** Players deduplicated by client connection (AI players always kept). */
    private List<GamePlayer> uniqueRecipients(List<GamePlayer> candidates) {
        List<GamePlayer> out = new ArrayList<>();
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (GamePlayer p : candidates) {
            if (p instanceof NetworkPlayer n && n.client() != null && !seen.add(n.client())) continue;
            out.add(p);
        }
        return out;
    }

    // ---- wire -------------------------------------------------------------------------------------

    /** Sends one message to every distinct client. */
    public void broadcast(String name, Map<String, Object> value) {
        for (GamePlayer p : uniqueRecipients(allPlayers())) p.send(name, value, 0);
    }

    /** SequenceMessage with the empty GUID so it executes exactly once (queued pump only). */
    private void broadcastStandalone(Map<String, Object> msg) {
        wire.lock();
        try {
            for (GamePlayer p : uniqueRecipients(allPlayers())) p.send("SequenceMessage", Msgs.standalone(gameId, msg), 0);
        } finally {
            wire.unlock();
        }
    }

    private final java.util.function.Supplier<String> ids = this::newEntityId;

    private String newEntityId() {
        return board.newId();
    }

    /** Sends a COMPLETE Start/Stop bracket to the given players. */
    void sendSequence(List<GamePlayer> to, GameSequence name, List<?> inner) {
        sendSequence(to, name.wire(), inner);
    }

    void sendSequence(List<GamePlayer> to, String name, List<?> inner) {
        wire.lock();
        try {
            for (GamePlayer p : uniqueRecipients(to)) {
                for (Map<String, Object> packet : Msgs.bracket(gameId, name, inner, ids)) p.send("SequenceMessage", packet, 0);
            }
        } finally {
            wire.unlock();
        }
    }

    private void sendSequenceAll(GameSequence name, List<?> inner) {
        sendSequence(allPlayers(), name, inner);
    }

    // ---- message helpers ----------------------------------------------------------------------------

    Map<String, Object> introduced(CardEntity c) {
        return Msgs.entityIntroduced(gameId, c);
    }

    Map<String, Object> moved(String entityId, String destId, int position) {
        return Msgs.entityMoved(board, entityId, destId, position);
    }

    Map<String, Object> dataEffect(String key, String entityId) {
        return Msgs.entityIdDataEffect(gameId, key, entityId);
    }

    // ---- state units / checkpoints --------------------------------------------------------------------

    private void runUnit(Runnable body) {
        UnitState st = unitState.get();
        synchronized (checkpointLock) {
            if (st.depth == 0) unitThreads++;
            st.depth++;
        }
        try {
            body.run();
        } finally {
            synchronized (checkpointLock) {
                st.depth--;
                if (st.depth == 0) {
                    unitThreads--;
                    if (st.prompting) {
                        promptThreads--;
                        st.prompting = false;
                    }
                }
                checkpointLock.notifyAll();
            }
        }
    }

    private void markPrompt() {
        UnitState st = unitState.get();
        synchronized (checkpointLock) {
            if (st.depth > 0 && !st.prompting) {
                st.prompting = true;
                promptThreads++;
                checkpointLock.notifyAll();
            }
        }
    }

    private void leavePrompt() {
        UnitState st = unitState.get();
        synchronized (checkpointLock) {
            if (st.prompting) {
                st.prompting = false;
                promptThreads--;
                checkpointLock.notifyAll();
            }
        }
    }

    /** Blocks until no state unit is mutating the board (or every active unit is parked at a prompt). */
    private void awaitStableState() {
        synchronized (checkpointLock) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (unitThreads > 0 && promptThreads < unitThreads && System.nanoTime() < deadline && !closed) {
                try {
                    checkpointLock.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ---- connection pause ------------------------------------------------------------------------------

    private boolean isPaused() {
        return !resumed;
    }

    /** Parks gameplay while any network player is disconnected or rebuilding its scene. */
    private void waitForConnectionResume() {
        if (resumed) {
            if (phase == GamePhase.GAME_OVER) throw new GameOverException();
            return;
        }
        markPrompt();
        try {
            synchronized (stateLock) {
                while (!resumed && phase != GamePhase.GAME_OVER) {
                    try {
                        stateLock.wait(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new GameOverException();
                    }
                }
            }
        } finally {
            leavePrompt();
        }
        if (phase == GamePhase.GAME_OVER) throw new GameOverException();
    }

    // ---- prompts -----------------------------------------------------------------------------------------

    private void sendIdleTimer(GamePlayer p, long remainingMs) {
        wire.lock();
        try {
            p.send("SetIdleTimer", Msgs.idleTimer(p.accountId, remainingMs), 0);
        } finally {
            wire.unlock();
        }
    }

    /**
     * Sends a selection prompt and waits inline for the reply. The prompt rides a standalone SequenceMessage with
     * the empty GUID so it executes exactly once. {@code expectedCounter} guards against STALE replies: a reply
     * echoing a different counter is discarded (and the offer re-sent) and the wait continues.
     */
    Reply prompt(GamePlayer player, String msgName, Map<String, Object> value, Integer expectedCounter,
                 Integer idleTimeoutMs) {
        if (phase == GamePhase.GAME_OVER) throw new GameOverException();
        waitForConnectionResume();
        boolean mainOffer = msgName.equals("SelectionWithTargetsAndActionsRequired");
        int idle = idleTimeoutMs != null ? idleTimeoutMs : (mainOffer ? settings.actionTimeoutMs : settings.followUpTimeoutMs);
        boolean timed = player instanceof NetworkPlayer;
        Map<String, Object> v = new LinkedHashMap<>(value);
        if (timed) {
            boolean hide = msgName.equals("CustomChoiceRequired") && Objects.equals(value.get("offerLength"), 0);
            v.put("offerLength", hide ? 0 : idle);
            v.put("startingTimestamp", now());
        }
        Map<String, Object> envelope = Msgs.standalone(gameId, Msgs.msg(msgName, v));
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        player.pendingChoice = future;
        player.pendingOffer = new GamePlayer.Offer("SequenceMessage", envelope, 0);
        long remaining = Math.max(0, idle);
        boolean timerRunning = false;
        wire.lock();
        try {
            player.send("SequenceMessage", envelope, 0);
            if (timed) {
                player.send("SetIdleTimer", Msgs.idleTimer(player.accountId, idle), 0);
                timerRunning = remaining > 0;
            }
        } finally {
            wire.unlock();
        }
        try {
            while (true) {
                markPrompt();
                String status = "reply";
                JsonNode reply = null;
                try {
                    if (player.isAi()) {
                        // An AI prompt never reaches the wire in practice (the engine asks the policy directly).
                        return new Reply(null, expectedCounter, false);
                    }
                    long waitStart = System.nanoTime();
                    CompletableFuture<JsonNode> f = player.pendingChoice;
                    while (true) {
                        if (f.isDone()) {
                            reply = futureValue(f);
                            break;
                        }
                        if (phase == GamePhase.GAME_OVER) throw new GameOverException();
                        if (isPaused()) {
                            status = "pause";
                            break;
                        }
                        long left = remaining - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStart);
                        if (timed && left <= 0) {
                            status = "timeout";
                            break;
                        }
                        try {
                            f.get(timed ? Math.min(left, 100) : 100, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException ignored) {
                            // re-check state
                        } catch (ExecutionException e) {
                            if (e.getCause() instanceof GameOverException g) throw g;
                            throw new IllegalStateException(e.getCause());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new GameOverException();
                        }
                    }
                    if (timed) {
                        remaining = Math.max(0, remaining - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStart));
                    }
                } finally {
                    leavePrompt();
                }
                if (phase == GamePhase.GAME_OVER) throw new GameOverException();
                if (status.equals("timeout") && !resumed) status = "pause";
                if (status.equals("pause")) {
                    synchronized (stateLock) {
                        if (disconnected.contains(player.accountId) || reconnecting.contains(player.accountId)) {
                            offersToRestore.add(player.accountId);
                        }
                    }
                    if (timerRunning) {
                        sendIdleTimer(player, 0);
                        timerRunning = false;
                    }
                    waitForConnectionResume();
                    if (remaining > 0) {
                        sendIdleTimer(player, remaining);
                        timerRunning = true;
                    }
                    continue;
                }
                if (status.equals("timeout")) {
                    wire.lock();
                    try {
                        player.send("SetIdleTimer", Msgs.idleTimer(player.accountId, 0), 0);
                        player.send("ForceSelectionFinished", obj(), 0);
                    } finally {
                        wire.unlock();
                    }
                    timerRunning = false;
                    pause(1500); // ForceSelectionFinished's client command clears the prompt override
                    LOG.info("[Session {}] {}'s selection timer expired ({}).", gameId, player.screenName(), msgName);
                    if (!mainOffer) {
                        endGame(opponentId(player.accountId), player.screenName() + " ran out of time to make a selection");
                    }
                    return new Reply(null, expectedCounter, true);
                }
                Integer replyCounter = reply != null && reply.hasNonNull("counter") ? reply.get("counter").asInt() : null;
                if (expectedCounter == null || replyCounter == null || replyCounter.equals(expectedCounter)) {
                    if (timerRunning) {
                        sendIdleTimer(player, 0);
                        timerRunning = false;
                    }
                    waitForConnectionResume();
                    return new Reply(reply == null ? null : reply.get("selection"), replyCounter, false);
                }
                LOG.info("[Session {}] Ignoring stale selection reply (counter {}, expected {}).", gameId, replyCounter,
                        expectedCounter);
                // Re-arm AND re-send: nothing else puts this offer back on the wire.
                GamePlayer.Offer pending = player.pendingOffer;
                player.pendingChoice = new CompletableFuture<>();
                if (pending != null) player.send(pending.name(), replayValue(pending.value()), pending.flags());
            }
        } finally {
            leavePrompt();
            if (timerRunning) sendIdleTimer(player, 0);
            player.pendingChoice = null;
            player.pendingOffer = null;
        }
    }

    private static JsonNode futureValue(CompletableFuture<JsonNode> f) {
        try {
            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GameOverException g) throw g;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameOverException();
        }
    }

    /** Copies an offer and restarts its client-side expiration clock. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> replayValue(Map<String, Object> envelope) {
        Map<String, Object> copy = new LinkedHashMap<>(envelope);
        Object msg = copy.get("msg");
        if (msg instanceof Map<?, ?> m) {
            Map<String, Object> mc = new LinkedHashMap<>((Map<String, Object>) m);
            Object val = mc.get("value");
            if (val instanceof Map<?, ?> vm && vm.containsKey("startingTimestamp")) {
                Map<String, Object> vc = new LinkedHashMap<>((Map<String, Object>) vm);
                vc.put("startingTimestamp", now());
                mc.put("value", vc);
            }
            copy.put("msg", mc);
        }
        return copy;
    }

    /**
     * Inbound reply from a player's connection (network thread): completes the outstanding offer. Replies from a
     * stale socket, or while play is paused for a reconnect, are ignored.
     */
    public void receivePlayerAction(String accountId, JsonNode action, ClientContext from) {
        GamePlayer player = players.get(accountId);
        if (player == null) return;
        if (from != null && player instanceof NetworkPlayer n && n.client() != from) {
            LOG.info("[Session {}] Ignoring action from stale socket for {}.", gameId, accountId);
            return;
        }
        if (!resumed && !restoringOffers.contains(accountId) && !player.isAi()) {
            LOG.info("[Session {}] Ignoring action from {} while paused for reconnection.", gameId, accountId);
            return;
        }
        CompletableFuture<JsonNode> f = player.pendingChoice;
        if (f != null && !f.isDone()) f.complete(action);
    }

    /** Button dialog (CustomChoiceRequired); returns the picked button index. AI players pick the first. */
    int promptChoice(String playerId, String promptText, List<String> buttons, String sortType) {
        GamePlayer player = players.get(playerId);
        if (player.isAi()) return 0;
        int counter = nextCounter(playerId);
        Reply r = prompt(player, "CustomChoiceRequired",
                Msgs.customChoiceRequired(gameId, counter, now(), promptText, buttons, sortType), counter, null);
        return !r.isNull() && r.selection().isInt() ? r.selection().asInt() : 0;
    }

    private void setOpponentsWaiting(String playerId, boolean waiting) {
        for (var e : players.entrySet()) {
            if (e.getKey().equals(playerId)) continue;
            if (waiting) sendPausePrompt(e.getValue(), PROMPT_WAIT_OPPONENT_DECISION, true);
            else sendClosePausePrompt(e.getValue());
        }
    }

    private void sendPausePrompt(GamePlayer p, String text, boolean remember) {
        if (p.isAi()) return;
        if (remember) {
            synchronized (stateLock) {
                pausePrompts.put(p.accountId, text);
            }
        }
        wire.lock();
        try {
            p.send("SequenceMessage", Msgs.standalone(gameId, Msgs.pauseOnPrompt(gameId, text)), 0);
        } finally {
            wire.unlock();
        }
    }

    private void sendClosePausePrompt(GamePlayer p) {
        if (p.isAi()) return;
        synchronized (stateLock) {
            pausePrompts.remove(p.accountId);
        }
        wire.lock();
        try {
            p.send("SequenceMessage", Msgs.standalone(gameId, Msgs.closePauseOnPrompt(gameId)), 0);
        } finally {
            wire.unlock();
        }
    }

    /** Opponent-deciding pick offer: collects the replied entityList (also used by effect selections). */
    List<String> runPickOffer(String playerId, String sourceEntityId, Map<String, Object> node, String targetType,
                              List<String> valid, int count, int minEffective, boolean forced) {
        GamePlayer player = players.get(playerId);
        if (player.isAi()) return new ArrayList<>(valid.subList(0, Math.min(Math.max(minEffective, Math.min(count, valid.size())), valid.size())));
        setOpponentsWaiting(playerId, true);
        try {
            for (int attempt = 0; attempt < MAX_SELECTION_RETRIES; attempt++) {
                int counter = nextCounter(playerId);
                Reply reply = prompt(player, "SelectionWithTargetsRequired",
                        Msgs.pickOffer(gameId, counter, now(), forced, targetType, sourceEntityId, node), counter, null);
                if (reply.isNull()) {
                    if (minEffective == 0) return List.of();
                    continue;
                }
                List<String> picked = new ArrayList<>();
                for (JsonNode response : selectionResponses(reply.selection())) {
                    if (response.isObject()) {
                        for (JsonNode t : response.path("entityList")) if (valid.contains(t.asText())) picked.add(t.asText());
                    } else if (response.isTextual() && valid.contains(response.asText())) {
                        picked.add(response.asText());
                    }
                }
                if (picked.size() > count) picked = new ArrayList<>(picked.subList(0, count));
                if (picked.size() >= minEffective) return picked;
                LOG.warn("[Session {}] Card pick under-picked ({}/{}); re-offering.", gameId, picked.size(), minEffective);
            }
            return new ArrayList<>(valid.subList(0, Math.min(minEffective, valid.size())));
        } finally {
            setOpponentsWaiting(playerId, false);
        }
    }

    /** The shapes a client has been seen to answer an EntityList pick with. */
    private static List<JsonNode> selectionResponses(JsonNode selection) {
        List<JsonNode> out = new ArrayList<>();
        if (selection == null) return out;
        if (selection.isObject()) {
            JsonNode r = selection.get("targetResponses");
            if (r != null && r.isArray()) r.forEach(out::add);
        } else if (selection.isArray()) {
            selection.forEach(out::add);
        } else if (selection.isTextual()) {
            out.add(selection);
        }
        return out;
    }

    @Override
    public List<CardEntity> pickEntities(String playerId, String sourceId, List<CardEntity> candidates, int count,
                                         int minimum, String promptText) {
        List<String> valid = new ArrayList<>();
        for (CardEntity c : candidates) valid.add(c.entityId);
        Map<String, Object> node = Msgs.entityListTargetInfo(valid, count, minimum, minimum > 0, SelectionKind.ENTITY_LIST.wire());
        node.put("targetPrompt", Msgs.text(promptText == null ? "" : promptText));
        String src = sourceId != null ? sourceId : candidates.get(0).entityId;
        List<String> picked = runPickOffer(playerId, src, node, SelectionKind.ENTITY_LIST.wire(), valid, count, minimum, minimum > 0);
        List<CardEntity> out = new ArrayList<>();
        for (String id : picked) {
            CardEntity c = board.card(id);
            if (c != null) out.add(c);
        }
        return out;
    }

    // ---- match start, readiness and the initial snapshot ----------------------------------------------

    /** gameOptions as the client expects them (cosmetics, tokens, elo; match options never hold secrets). */
    Map<String, Object> buildGameOptions() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("turnTime", "45");
        o.put("benchTime", "15");
        o.put("theme", "ForestPlaymat");
        for (var e : players.entrySet()) {
            String id = e.getKey();
            GamePlayer p = e.getValue();
            o.put("avatarProfile_name_" + id, p.screenName());
            o.put("avatarProfile_" + id, String.join(",", p.avatarItems()));
            o.put("gameExtrasSleeve_" + id, p.sleeveId());
            o.put("gameExtrasCoin_" + id, p.coinId());
            // Image name -> textured box from deckboxFlats/<name>; "" -> red/white TwoTone (flats are not ported).
            o.put("gameExtrasDeckBox_" + id, "");
            o.put("gameExtrasDeckImage_" + id, "");
            o.put("gameExtrasKeeperDeckBox_" + id, "false");
        }
        List<String> tokens = new ArrayList<>();
        for (String id : players.keySet()) {
            var pe = board.playerEntity(id);
            if (pe == null) continue;
            if (Boolean.TRUE.equals(pe.get(Attr.HAS_GX_TOKEN)) && !tokens.contains(TOKEN_GX)) tokens.add(TOKEN_GX);
            if (Boolean.TRUE.equals(pe.get(Attr.HAS_VSTAR_TOKEN)) && !tokens.contains(TOKEN_VSTAR)) tokens.add(TOKEN_VSTAR);
        }
        if (!tokens.isEmpty()) o.put(GAME_OPTION_TOKENS_KEY, String.join(",", tokens));
        // showPlayerUpsetNUX float.Parses gameOptions["eloRating_<id>"] unguarded: the key must exist for everyone.
        for (var e : players.entrySet()) {
            o.put("eloRating_" + e.getKey(), String.valueOf(e.getValue().isAi() ? 1000 : hooks.rating(e.getKey())));
        }
        return o;
    }

    Map<String, Object> matchFoundPayload(boolean reconnecting) {
        Map<String, Object> opts = board.gameOptions;
        if (opts.keySet().stream().noneMatch(k -> k.startsWith("eloRating_"))) {
            opts = buildGameOptions();
            board.gameOptions = opts;
        }
        if (reconnecting) {
            // Flag on a copy so it never leaks into the persistent SGS gameOptions.
            opts = new LinkedHashMap<>(opts);
            opts.put(GAME_OPTION_RECONNECTING_KEY, GAME_OPTION_RECONNECTING_VALUE);
        }
        return obj("gameID", gameId, "players", new ArrayList<>(players.keySet()), "gameOptions", opts);
    }

    /** Fires MatchFound to every client: the scene transition starts, each client answers PlayerReady. */
    public void start() {
        LOG.info("[Session {}] Starting GameSession.", gameId);
        board.gameOptions = buildGameOptions();
        broadcast("MatchFound", matchFoundPayload(false));
        // A match with no network player (AI vs AI, tests) has nobody to wait for.
        dispatchInitialStateIfReady();
    }

    /** PlayerReady from a client's playmat scene. */
    public void markPlayerReady(String accountId, ClientContext from) {
        GamePlayer player = players.get(accountId);
        if (from != null && player instanceof NetworkPlayer n && n.client() != from) {
            LOG.info("[Session {}] Ignoring PlayerReady from stale socket for {}.", gameId, accountId);
            return;
        }
        boolean resync = false;
        synchronized (stateLock) {
            if (reconnecting.contains(accountId)) {
                if (!stateDispatched) {
                    reconnecting.remove(accountId);
                    cancelGrace(accountId);
                    readyPlayers.add(accountId);
                } else {
                    resync = true;
                }
            } else {
                readyPlayers.add(accountId);
            }
        }
        if (resync) {
            resendStateTo(accountId);
            return;
        }
        dispatchInitialStateIfReady();
    }

    /** Sends the first snapshot after every network scene is ready, then starts the gameplay thread. */
    private void dispatchInitialStateIfReady() {
        synchronized (stateLock) {
            if (stateDispatched) return;
            if (!disconnected.isEmpty() || !reconnecting.isEmpty()
                    || !readyPlayers.containsAll(networkPlayers().keySet())) return;
            stateDispatched = true;
        }
        LOG.info("[Session {}] All network players ready. Dispatching SerializedGameState.", gameId);
        sendSerializedGameState(null);
        synchronized (stateLock) {
            resyncPlayers.clear();
            resyncReady.clear();
            offersToRestore.clear();
            restoringOffers.clear();
            resumed = true;
            stateLock.notifyAll();
        }
        gameplayThread = Thread.ofVirtual().name("game-" + gameId.substring(0, 8)).start(this::runGameplaySequence);
    }

    /** SerializedGameState wrapped in a standalone SequenceMessage; one viewer-scoped snapshot per client. */
    void sendSerializedGameState(String onlyPlayerId) {
        List<GamePlayer> recipients = onlyPlayerId != null ? List.of(players.get(onlyPlayerId)) : uniqueRecipients(allPlayers());
        wire.lock();
        try {
            for (GamePlayer p : recipients) {
                Map<String, Object> state = board.serialize(p.accountId);
                p.send("SequenceMessage", Msgs.standalone(gameId, Msgs.msg("SerializedGameState", state)), 0);
            }
        } finally {
            wire.unlock();
        }
    }

    private void runGameplaySequence() {
        try {
            runUnit(this::runPregameCoinFlip);
            waitForConnectionResume();
            runUnit(this::runSetupPhase);
            waitForConnectionResume();
            runPlacementPhase();
            runTurnLoop();
        } catch (GameOverException e) {
            LOG.info("[Session {}] Gameplay sequence finished (game over).", gameId);
        } catch (Throwable t) {
            LOG.error("[Session {}] Error in gameplay sequence", gameId, t);
            if (phase != GamePhase.GAME_OVER) {
                try {
                    String winner = crashFallbackWinner();
                    if (winner != null) endGame(winner, "A game error occurred.");
                } catch (GameOverException ignored) {
                    // expected: end_game unwinds
                } catch (Throwable inner) {
                    LOG.error("[Session {}] Best-effort endGame failed", gameId, inner);
                }
            }
        } finally {
            close();
        }
    }

    /** Winner for a crash-abort ending: prefer the non-acting player, then any connected player. */
    private String crashFallbackWinner() {
        if (turn.activePlayerId != null) {
            String opp = opponentId(turn.activePlayerId);
            if (players.containsKey(opp)) return opp;
        }
        for (var e : players.entrySet()) if (e.getValue() instanceof NetworkPlayer n && n.connected()) return e.getKey();
        return players.keySet().stream().findFirst().orElse(null);
    }

    /** Releases the session through its owner. */
    public void close() {
        if (closed) return;
        closed = true;
        if (onClose != null) onClose.accept(gameId);
        cleanup();
    }

    /** Cancels pending futures, grace timers and the gameplay thread. */
    public void cleanup() {
        closed = true;
        for (GamePlayer p : players.values()) {
            CompletableFuture<JsonNode> f = p.pendingChoice;
            if (f != null && !f.isDone()) f.completeExceptionally(new GameOverException());
        }
        synchronized (stateLock) {
            for (Thread t : graceTimers.values()) t.interrupt();
            graceTimers.clear();
            stateLock.notifyAll();
        }
        Thread t = gameplayThread;
        if (t != null && t != Thread.currentThread() && t.isAlive() && phase != GamePhase.GAME_OVER) t.interrupt();
    }

    /** Runs tasks concurrently (the two players' setup choices) and rethrows the first failure. */
    private void parallel(List<Runnable> tasks) {
        if (tasks.size() == 1) {
            tasks.get(0).run();
            return;
        }
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (Runnable r : tasks) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GameOverException();
            }
        }
        if (!errors.isEmpty()) {
            Throwable t = errors.stream().filter(e -> !(e instanceof GameOverException)).findFirst().orElse(errors.get(0));
            if (t instanceof RuntimeException r) throw r;
            throw new IllegalStateException(t);
        }
    }

    // ---- pregame coin flip -------------------------------------------------------------------------------

    private void runPregameCoinFlip() {
        List<String> ids = new ArrayList<>(players.keySet());
        String callerId = ids.get(rng.nextInt(ids.size()));
        String opponentId = ids.stream().filter(p -> !p.equals(callerId)).findFirst().orElseThrow();
        coinFlipCallerId = callerId;
        GamePlayer caller = players.get(callerId), opponent = players.get(opponentId);
        LOG.info("[Session {}] Selected {} to call the coin toss.", gameId, caller.screenName());

        // The "opponent is picking Heads or Tails" coin screen, sent complete (Start + offer + Stop).
        sendSequence(List.of(opponent), GameSequence.OPPONENT_PICKING_HEADS_OR_TAILS,
                List.of(Msgs.customChoiceOffer(gameId, callerId, List.of(PROMPT_HEADS, PROMPT_TAILS))));
        phase = GamePhase.COIN_FLIP_WAIT_CHOICE;
        int choice;
        if (caller.isAi()) {
            pause(1500);
            choice = rng.nextBoolean() ? 0 : 1;
        } else {
            Reply r = prompt(caller, "CoinFlipChoiceRequired",
                    Msgs.pregameChoice(gameId, 1, now(), PROMPT_SORT_COIN, List.of(PROMPT_HEADS, PROMPT_TAILS)), null, null);
            choice = !r.isNull() && r.selection().isInt() ? r.selection().asInt() : -1;
        }
        int actual = rng.nextBoolean() ? 0 : 1;
        statAdd(callerId, actual == 0 ? "headsflipped" : "tailsflipped", 1);
        var playerEntity = board.playerEntity(callerId);
        String callerEntityId = playerEntity != null ? playerEntity.entityId : EMPTY_SEQUENCE_ID;
        sendSequenceAll(GameSequence.INITIAL_COIN_FLIP, List.of(Msgs.coinFlip(gameId, List.of(actual),
                TEXT_COIN_TOSS_RESULT, actual == 1 ? TEXT_TAILS_RESULT : TEXT_HEADS_RESULT, callerEntityId)));
        String winnerId = choice == actual ? callerId : opponentId;
        coinFlipWinnerId = winnerId;
        GamePlayer winner = players.get(winnerId);
        String loserId = ids.stream().filter(p -> !p.equals(winnerId)).findFirst().orElseThrow();
        LOG.info("[Session {}] {} called {}, coin landed {} -> {} won the coin toss.", gameId, caller.screenName(),
                choice == 1 ? PROMPT_TAILS : PROMPT_HEADS, actual == 1 ? PROMPT_TAILS : PROMPT_HEADS, winner.screenName());
        // Let the flip animation render before the next dialog state.
        pause(settings.coinAnimMs);

        sendSequence(List.of(players.get(loserId)), GameSequence.OPPONENT_CHOOSING_TO_GO_FIRST,
                List.of(Msgs.customChoiceOffer(gameId, winnerId, List.of(PROMPT_YES, PROMPT_NO))));
        phase = GamePhase.COIN_FLIP_WAIT_GO_FIRST;
        int goFirst;
        if (winner.isAi()) {
            pause(1500);
            goFirst = 0;
        } else {
            Reply r = prompt(winner, "GoFirstChoiceRequired",
                    Msgs.pregameChoice(gameId, 2, now(), PROMPT_SORT_GO_FIRST, List.of(PROMPT_YES, PROMPT_NO)), null, null);
            goFirst = !r.isNull() && r.selection().isInt() ? r.selection().asInt() : -1;
        }
        firstPlayerId = goFirst == 0 ? winnerId : loserId;
        board.firstPlayerId = firstPlayerId;
        LOG.info("[Session {}] {} will go first.", gameId, players.get(firstPlayerId).screenName());
        // The empty bracket dismisses the coin screen without starting a client turn.
        sendSequenceAll(GameSequence.ACTIVE_PLAYER_SET, List.of());
        phase = GamePhase.MULLIGAN_PHASE;
    }

    // ---- setup: shuffle, deal ------------------------------------------------------------------------------

    private void broadcastShuffle(String playerId) {
        board.shuffleDeck(playerId);
        PlayArea deck = board.area(playerId, "deck");
        if (deck == null) return;
        broadcastStandalone(Msgs.shuffled(gameId, deck.entityId));
    }

    private void runSetupPhase() {
        phase = GamePhase.DEAL_HANDS;
        for (String pid : turnOrder()) broadcastShuffle(pid);
        pause(settings.shuffleAnimMs);
        Map<String, List<Board.Moved>> dealt = new LinkedHashMap<>();
        for (String pid : turnOrder()) {
            dealt.put(pid, board.drawCards(pid, STARTING_HAND_SIZE));
            LOG.info("[Session {}] Dealt {} cards to {}.", gameId, dealt.get(pid).size(), players.get(pid).screenName());
        }
        sendHandDeal(dealt);
        phase = GamePhase.SETUP_PHASE;
    }

    /** Animates hand deals for several players in one parallel DealInitialHands bracket. */
    private void sendHandDeal(Map<String, List<Board.Moved>> dealt) {
        List<NestedSequence> grouped = new ArrayList<>();
        for (String pid : turnOrder()) {
            List<Board.Moved> d = dealt.get(pid);
            if (d == null || d.isEmpty()) continue;
            List<Map<String, Object>> moves = new ArrayList<>();
            for (Board.Moved m : d) moves.add(moved(m.entityId(), m.destinationId(), m.position()));
            grouped.add(new NestedSequence(GameSequence.GROUPED_MOVE, moves));
        }
        if (grouped.isEmpty()) return;
        for (var e : players.entrySet()) {
            GamePlayer p = e.getValue();
            wire.lock();
            try {
                // Own-card reveals go out BEFORE the bracket (in-bracket intros would apply only after the deal).
                for (Board.Moved m : dealt.getOrDefault(e.getKey(), List.of())) {
                    p.send("SequenceMessage", Msgs.standalone(gameId, introduced(m.card())), 0);
                }
            } finally {
                wire.unlock();
            }
            sendSequence(List.of(p), GameSequence.DEAL_INITIAL_HANDS, grouped);
        }
    }

    private boolean needsMulligan(String pid) {
        return board.setupActiveCandidates(pid).isEmpty() && board.playerHasAnyBasic(pid);
    }

    /** Mulligans the players together until one holds a Basic, shown as one mulligan each. */
    private void resolveMulligans(List<String> playerIds, Map<String, Integer> counts,
                                  Map<String, List<Map<String, Object>>> revealPiles) {
        Map<String, List<String>> returned = new LinkedHashMap<>();
        while (!playerIds.isEmpty() && playerIds.stream().allMatch(this::needsMulligan)) {
            for (String pid : playerIds) {
                List<CardEntity> hand = board.cardsIn(pid, "hand");
                returned.computeIfAbsent(pid, k -> hand.stream().map(c -> c.entityId).toList());
                Map<String, Object> pile = new LinkedHashMap<>();
                for (CardEntity c : hand) pile.put(c.entityId, c.serializeAttributes());
                revealPiles.get(pid).add(pile);
                counts.merge(pid, 1, Integer::sum);
                LOG.info("[Session {}] {} mulligans (#{}).", gameId, players.get(pid).screenName(), counts.get(pid));
                board.returnHandToDeck(pid);
                board.shuffleDeck(pid);
                board.drawCards(pid, STARTING_HAND_SIZE);
            }
        }
        if (returned.isEmpty()) return;
        for (var e : returned.entrySet()) {
            PlayArea deck = board.area(e.getKey(), "deck");
            List<Map<String, Object>> back = new ArrayList<>();
            for (int i = 0; i < e.getValue().size(); i++) back.add(moved(e.getValue().get(i), deck.entityId, i));
            sendSequenceAll(GameSequence.MULLIGAN, List.of(new NestedSequence(GameSequence.GROUPED_MOVE, back),
                    Msgs.shuffled(gameId, deck.entityId)));
        }
        pause(settings.shuffleAnimMs);
        Map<String, List<Board.Moved>> redealt = new LinkedHashMap<>();
        for (String pid : returned.keySet()) {
            PlayArea hand = board.area(pid, "hand");
            List<Board.Moved> l = new ArrayList<>();
            for (int i = 0; i < hand.children.size(); i++) {
                l.add(new Board.Moved(hand.children.get(i).entityId, hand.entityId, i, (CardEntity) hand.children.get(i)));
            }
            redealt.put(pid, l);
        }
        sendHandDeal(redealt);
    }

    /** Draws cards server-side and animates them in a Draw bracket (faces only for the drawer). */
    private List<Board.Moved> broadcastDraw(String playerId, int count) {
        List<Board.Moved> drawn = board.drawCards(playerId, count);
        if (drawn.isEmpty()) return drawn;
        statAdd(playerId, "cardsdrawn", drawn.size());
        List<Map<String, Object>> moves = new ArrayList<>();
        List<Map<String, Object>> intros = new ArrayList<>();
        for (Board.Moved d : drawn) {
            moves.add(moved(d.entityId(), d.destinationId(), d.position()));
            intros.add(introduced(d.card()));
        }
        NestedSequence nested = new NestedSequence(GameSequence.GROUPED_MOVE, moves);
        for (var e : players.entrySet()) {
            List<Object> inner = new ArrayList<>();
            if (e.getKey().equals(playerId)) inner.addAll(intros);
            inner.add(nested);
            sendSequence(List.of(e.getValue()), GameSequence.DRAW, inner);
        }
        return drawn;
    }

    /** Shows each player's mulliganed hands to both clients via the reveal carousel. */
    private void revealMulligans(Map<String, List<Map<String, Object>>> revealPiles) {
        for (String pid : turnOrder()) {
            List<Map<String, Object>> piles = revealPiles.get(pid);
            if (piles == null || piles.isEmpty()) continue;
            PlayArea deck = board.area(pid, "deck");
            sendSequenceAll(GameSequence.REVEAL_MULLIGANS, List.of(Msgs.mulliganReveal(gameId, pid,
                    players.get(pid).screenName(), piles, deck == null ? null : deck.entityId)));
        }
    }

    /** Offers the less-mulliganed player one Yes/No extra draw per net mulligan. */
    private Map<String, List<String>> offerExtraDraws(Map<String, Integer> counts) {
        Map<String, List<String>> drawnBy = new LinkedHashMap<>();
        for (String pid : turnOrder()) {
            String opp = opponentId(pid);
            int net = counts.getOrDefault(opp, 0) - counts.getOrDefault(pid, 0);
            if (net <= 0) continue;
            String promptText = MessageFormat.format(PROMPT_MULLIGAN_EXTRA_DRAW, counts.getOrDefault(opp, 0));
            int remaining = net;
            while (remaining > 0) {
                List<String> buttons = new ArrayList<>(List.of(MessageFormat.format(PROMPT_YES_COUNT, remaining), PROMPT_NO));
                if (remaining > 1) buttons.add(MessageFormat.format(PROMPT_YES_COUNT, 1));
                int picked = promptChoice(pid, promptText, buttons, PROMPT_SORT_MULLIGAN);
                int count;
                if (picked == 0) count = remaining;
                else if (picked == 2) count = 1;
                else break;
                List<Board.Moved> drawn = broadcastDraw(pid, count);
                if (drawn.isEmpty()) break;
                for (Board.Moved d : drawn) drawnBy.computeIfAbsent(pid, k -> new ArrayList<>()).add(d.entityId());
                remaining -= drawn.size();
            }
        }
        return drawnBy;
    }

    /** Mulligans and setup in original PTCGO order; Basic holders set up first. */
    private void runPlacementPhase() {
        phase = GamePhase.PLACEMENT_PHASE;
        List<String> order = turnOrder();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, List<Map<String, Object>>> revealPiles = new HashMap<>();
        for (String pid : players.keySet()) {
            counts.put(pid, 0);
            revealPiles.put(pid, new ArrayList<>());
        }
        for (String pid : order) {
            if (!board.playerHasAnyBasic(pid)) {
                LOG.error("[Session {}] Deck of {} contains no Basic Pokemon; skipping impossible mulligan.", gameId,
                        players.get(pid).screenName());
            }
        }
        List<String> waiting = new ArrayList<>(order.stream().filter(this::needsMulligan).toList());
        if (waiting.size() > 1) {
            phase = GamePhase.MULLIGAN_PHASE;
            waitForConnectionResume();
            List<String> w = waiting;
            runUnit(() -> resolveMulligans(w, counts, revealPiles));
            waiting = new ArrayList<>(waiting.stream().filter(this::needsMulligan).toList());
        }
        Set<String> done = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final List<String> waitingNow = waiting;
        List<String> first = waiting.isEmpty() ? List.of() : order.stream().filter(p -> !waitingNow.contains(p)).toList();
        if (!waiting.isEmpty()) {
            if (!first.isEmpty()) {
                phase = GamePhase.PLACEMENT_PHASE;
                sendSetupWaitPrompts(waiting);
                runSetups(first, done);
                closeSetupPrompts();
            }
            phase = GamePhase.MULLIGAN_PHASE;
            waitForConnectionResume();
            List<String> w = waiting;
            runUnit(() -> resolveMulligans(w, counts, revealPiles));
        }
        waitForConnectionResume();
        runUnit(() -> revealMulligans(revealPiles));

        phase = GamePhase.PLACEMENT_PHASE;
        sendSetupWaitPrompts(first);
        runSetups(waiting.isEmpty() ? order : waiting, done);
        closeSetupPrompts();

        phase = GamePhase.MULLIGAN_PHASE;
        waitForConnectionResume();
        Map<String, List<String>>[] extra = new Map[1];
        runUnit(() -> extra[0] = offerExtraDraws(counts));
        if (!extra[0].isEmpty()) {
            phase = GamePhase.PLACEMENT_PHASE;
            Set<String> doneAfterDraw = new HashSet<>(players.keySet());
            doneAfterDraw.removeAll(extra[0].keySet());
            done.clear();
            done.addAll(doneAfterDraw);
            sendSetupWaitPrompts(doneAfterDraw);
            List<Runnable> tasks = new ArrayList<>();
            for (var e : extra[0].entrySet()) {
                tasks.add(() -> runUnit(() -> runBenchPlacement(e.getKey(), done, new HashSet<>(e.getValue()))));
            }
            parallel(tasks);
            closeSetupPrompts();
        }
        phase = GamePhase.PRIZE_DEAL;
        waitForConnectionResume();
        runUnit(this::dealPrizeCards);
        waitForConnectionResume();
        runUnit(this::introduceInitialPokemon);
        phase = GamePhase.SETUP_COMPLETE;
        LOG.info("[Session {}] Setup complete; board is ready for turn 1.", gameId);
    }

    private void closeSetupPrompts() {
        for (GamePlayer p : players.values()) {
            waitForConnectionResume();
            sendClosePausePrompt(p);
        }
    }

    private void sendSetupWaitPrompts(Iterable<String> ids) {
        for (String pid : ids) {
            GamePlayer p = players.get(pid);
            if (p instanceof NetworkPlayer) {
                waitForConnectionResume();
                sendPausePrompt(p, PROMPT_WAIT_OPPONENT_SETUP, true);
            }
        }
    }

    /** Runs the given players' Active and Bench picks concurrently. */
    private void runSetups(List<String> playerIds, Set<String> done) {
        List<Runnable> tasks = new ArrayList<>();
        for (String pid : playerIds) {
            tasks.add(() -> runUnit(() -> {
                runActivePlacement(pid);
                runBenchPlacement(pid, done, null);
            }));
        }
        parallel(tasks);
    }

    private Map<String, Object> placementOffer(String pid, String prompt, List<CardEntity> cards, String targetType) {
        return Msgs.placementOffer(gameId, nextCounter(pid), now(), prompt, cards.stream().map(c -> c.entityId).toList(), targetType);
    }

    /** The placed card id of a placement reply ({"entityID": ...}), or null when not valid. */
    private String parsePlacementReply(Reply reply, List<CardEntity> candidates) {
        if (reply.isNull()) return null;
        JsonNode sel = reply.selection();
        String id = null;
        if (sel.isObject()) {
            if (sel.hasNonNull("entityID")) id = sel.get("entityID").asText();
            else if (sel.path("targetResponses").isArray() && sel.path("targetResponses").size() > 0) {
                JsonNode list = sel.path("targetResponses").get(0).path("entityList");
                if (list.isArray() && list.size() > 0) id = list.get(0).asText();
            }
        } else if (sel.isTextual()) {
            id = sel.asText();
        } else {
            LOG.warn("[Session {}] Malformed placement reply: {}", gameId, sel);
            return null;
        }
        String found = id;
        if (found == null || candidates.stream().noneMatch(c -> c.entityId.equals(found))) {
            LOG.warn("[Session {}] Placement reply picked non-offered entity {}; re-offering.", gameId, id);
            return null;
        }
        return found;
    }

    /** One player's required Active Pokemon pick (the bare offer is unforced: a forced root would say "End Turn"). */
    private void runActivePlacement(String pid) {
        GamePlayer player = players.get(pid);
        PlayArea active = board.area(pid, "activePokemonArea");
        if (active == null) {
            LOG.error("[Session {}] Missing active area for {}.", gameId, pid);
            return;
        }
        if (player.isAi()) {
            List<CardEntity> basics = board.setupActiveCandidates(pid);
            if (!basics.isEmpty()) {
                CardEntity choice = basics.size() > 1 && aiPolicy.usesFullPolicy(player) ? aiPolicy.bestSetupActive(basics) : basics.get(0);
                placeSetupCard(pid, choice.entityId, active);
            }
            return;
        }
        sendPausePrompt(player, PROMPT_CHOOSE_ACTIVE, true);
        for (int i = 0; i < MAX_SELECTION_RETRIES; i++) {
            if (!active.children.isEmpty()) break;
            List<CardEntity> basics = board.setupActiveCandidates(pid);
            if (basics.isEmpty()) {
                LOG.error("[Session {}] {} has no Basic to place as Active; skipping placement.", gameId, player.screenName());
                break;
            }
            Map<String, Object> offer = placementOffer(pid, PROMPT_CHOOSE_ACTIVE, basics, TARGET_TYPE_ACTIVE);
            Reply reply = prompt(player, "SelectionWithTargetsRequired", offer, (Integer) offer.get("counter"), null);
            String picked = parsePlacementReply(reply, basics);
            if (picked != null) placeSetupCard(pid, picked, active);
        }
        // The bench offer that follows needs its Done button back.
        sendClosePausePrompt(player);
    }

    private void runBenchPlacement(String pid, Set<String> done, Set<String> candidates) {
        GamePlayer player = players.get(pid);
        PlayArea bench = board.area(pid, "bench");
        if (bench == null) {
            LOG.error("[Session {}] Missing bench area for {}.", gameId, pid);
            return;
        }
        try {
            if (player.isAi()) {
                for (CardEntity c : benchCandidates(pid, candidates)) {
                    if (bench.children.size() >= BENCH_CAPACITY) break;
                    placeSetupCard(pid, c.entityId, bench);
                }
                return;
            }
            for (int i = 0; i < MAX_SELECTION_RETRIES; i++) {
                if (bench.children.size() >= BENCH_CAPACITY) {
                    LOG.info("[Session {}] {}'s bench is full.", gameId, player.screenName());
                    break;
                }
                List<CardEntity> basics = benchCandidates(pid, candidates);
                Map<String, Object> offer = placementOffer(pid, PROMPT_CHOOSE_BENCH, basics, TARGET_TYPE_BENCH);
                Reply reply = prompt(player, "SelectionWithTargetsRequired", offer, (Integer) offer.get("counter"), null);
                if (reply.isNull()) { // null-selection Advance ("Done" button) = finished placing
                    LOG.info("[Session {}] {} is done benching.", gameId, player.screenName());
                    break;
                }
                String picked = parsePlacementReply(reply, basics);
                if (picked != null) placeSetupCard(pid, picked, bench);
            }
        } finally {
            done.add(pid);
        }
        if (player instanceof NetworkPlayer) {
            if (done.contains(opponentId(pid))) sendClosePausePrompt(player);
            else sendPausePrompt(player, PROMPT_WAIT_OPPONENT_SETUP, true);
        }
    }

    private List<CardEntity> benchCandidates(String pid, Set<String> only) {
        List<CardEntity> out = new ArrayList<>();
        for (CardEntity c : board.setupBenchCandidates(pid)) if (only == null || only.contains(c.entityId)) out.add(c);
        return out;
    }

    /** Moves a setup card into an area and echoes it as an InitialPick bracket. */
    private boolean placeSetupCard(String pid, String cardId, PlayArea area) {
        int position = "bench".equals(area.areaName()) ? board.freeBenchSlot(pid) : area.children.size();
        if (!board.moveCard(cardId, area.entityId)) {
            LOG.warn("[Session {}] Could not move {} into {}.", gameId, cardId, area.areaName());
            return false;
        }
        LOG.info("[Session {}] {} placed {} into {}.", gameId, players.get(pid).screenName(), cardId, area.areaName());
        sendSequenceAll(GameSequence.INITIAL_PICK, List.of(moved(cardId, area.entityId, position)));
        return true;
    }

    /** Reveals each player's placed Pokemon to their opponent (simultaneous flip). */
    private void introduceInitialPokemon() {
        for (var e : players.entrySet()) {
            String opp = opponentId(e.getKey());
            List<Map<String, Object>> intros = new ArrayList<>();
            for (String area : List.of("activePokemonArea", "bench")) {
                for (CardEntity c : board.cardsIn(opp, area)) intros.add(introduced(c));
            }
            if (!intros.isEmpty()) sendSequence(List.of(e.getValue()), GameSequence.INTRODUCE_INITIAL_POKEMON, intros);
        }
    }

    /** Deals PRIZE_COUNT face-down prize cards per player in one parallel bracket (no intros: prizes stay hidden). */
    private void dealPrizeCards() {
        List<NestedSequence> nested = new ArrayList<>();
        for (String pid : turnOrder()) {
            List<Board.Moved> dealt = board.dealFromDeck(pid, "prizePile", PRIZE_COUNT);
            LOG.info("[Session {}] Dealt {} prize cards to {}.", gameId, dealt.size(), players.get(pid).screenName());
            List<Map<String, Object>> moves = new ArrayList<>();
            for (Board.Moved d : dealt) moves.add(moved(d.entityId(), d.destinationId(), d.position()));
            if (!moves.isEmpty()) nested.add(new NestedSequence(GameSequence.GROUPED_MOVE, moves));
        }
        if (!nested.isEmpty()) sendSequenceAll(GameSequence.DEAL_INITIAL_PRIZE_CARDS, nested);
    }

    // ---- turn loop ---------------------------------------------------------------------------------------------

    private void runTurnLoop() {
        phase = GamePhase.TURN_LOOP;
        List<String> order = turnOrder();
        if (order.isEmpty()) return;
        String activeId = order.get(turn.turnNumber % order.size());
        for (int i = 0; i < MAX_TURNS; i++) {
            waitForConnectionResume();
            String a = activeId;
            runUnit(() -> beginTurn(a));
            runPlayerTurn(activeId);
            waitForConnectionResume();
            runUnit(() -> runPokemonCheckup(a));
            activeId = opponentId(activeId);
        }
        LOG.error("[Session {}] MAX_TURNS ({}) reached; stopping the turn loop.", gameId, MAX_TURNS);
    }

    /** Advances the turn counter, announces the active player and draws. */
    private void beginTurn(String activeId) {
        turn.beginTurn(activeId);
        GamePlayer player = players.get(activeId);
        LOG.info("[Session {}] Turn {} begins for {}.", gameId, turn.turnNumber, player.screenName());
        List<Board.Moved> drawn = board.drawCards(activeId, 1);
        if (drawn.isEmpty()) {
            // Failing the mandatory turn draw loses the game (deck out).
            endGame(opponentId(activeId), player.screenName() + " has no cards left to draw");
        }
        for (Board.Moved d : drawn) turn.turnDrawEntityIds.add(d.entityId());
        sendSequenceAll(GameSequence.ACTIVE_PLAYER_SET, List.of(Msgs.activePlayerSet(gameId, activeId)));
        // Top-level Draw bracket (never nested inside ActivePlayerSet); the move rides a nested GroupedMove.
        if (!drawn.isEmpty()) {
            statAdd(activeId, "cardsdrawn", drawn.size());
            List<Map<String, Object>> moves = new ArrayList<>();
            List<Map<String, Object>> intros = new ArrayList<>();
            for (Board.Moved d : drawn) {
                moves.add(moved(d.entityId(), d.destinationId(), d.position()));
                intros.add(introduced(d.card()));
            }
            NestedSequence nested = new NestedSequence(GameSequence.GROUPED_MOVE, moves);
            for (var e : players.entrySet()) {
                List<Object> inner = new ArrayList<>();
                if (e.getKey().equals(activeId)) inner.addAll(intros);
                inner.add(nested);
                sendSequence(List.of(e.getValue()), GameSequence.DRAW, inner);
            }
        }
    }

    /** Offers recomputed legal actions until the player ends their turn. */
    private void runPlayerTurn(String activeId) {
        GamePlayer player = players.get(activeId);
        if (player.isAi()) {
            // Make sure no human's countdown is left running during the bot's turn.
            for (GamePlayer other : players.values()) if (other instanceof NetworkPlayer) sendIdleTimer(other, 0);
            runAiTurn(activeId);
            return;
        }
        for (int i = 0; i < MAX_ACTIONS_PER_TURN; i++) {
            waitForConnectionResume();
            List<LegalAction> legal = LegalActions.compute(board, turn, activeId, gameId, registry);
            LOG.info("[Session {}] Offering {} {} legal action(s) on turn {}.", gameId, player.screenName(), legal.size(),
                    turn.turnNumber);
            int counter = nextCounter(activeId);
            List<Map<String, Object>> targetMap = new ArrayList<>();
            for (LegalAction a : legal) targetMap.add(a.wire(gameId));
            Reply reply = prompt(player, "SelectionWithTargetsAndActionsRequired",
                    Msgs.mainOffer(gameId, counter, now(), targetMap), counter, settings.actionTimeoutMs);
            if (reply.isNull()) {
                LOG.info("[Session {}] {} {}.", gameId, player.screenName(),
                        reply.timedOut() ? "was skipped after the action timeout" : "ends turn");
                return;
            }
            Object[] parsed = parseActionReply(reply.selection(), legal);
            if (parsed == null) continue;
            LegalAction entry = (LegalAction) parsed[0];
            @SuppressWarnings("unchecked") List<String> targetIds = (List<String>) parsed[1];
            boolean[] over = new boolean[1];
            runUnit(() -> over[0] = applyPlayerAction(activeId, entry, targetIds));
            if (over[0]) return;
        }
        LOG.warn("[Session {}] {} hit the per-turn action cap ({}); forcing end of turn.", gameId, player.screenName(),
                MAX_ACTIONS_PER_TURN);
    }

    /**
     * Validates a reply ([[entityID, actionID], [targetResponses]]) against the offer; returns
     * {LegalAction, List targetIds} or null to re-offer.
     */
    private Object[] parseActionReply(JsonNode selection, List<LegalAction> legal) {
        if (selection == null || !selection.isArray() || selection.isEmpty()) {
            LOG.warn("[Session {}] Malformed action reply: {}", gameId, selection);
            return null;
        }
        JsonNode head = selection.get(0);
        if (!head.isArray() || head.size() < 2) {
            LOG.warn("[Session {}] Malformed action head: {}", gameId, head);
            return null;
        }
        String entityId = head.get(0).asText(), actionId = head.get(1).asText();
        LegalAction entry = legal.stream().filter(a -> a.entityId().equals(entityId) && a.actionId().equals(actionId))
                .findFirst().orElse(null);
        if (entry == null) {
            LOG.warn("[Session {}] Reply picked non-offered action {} on {}; re-offering.", gameId, actionId, entityId);
            return null;
        }
        List<String> targets = new ArrayList<>();
        if (selection.size() > 1 && selection.get(1).isArray()) {
            for (JsonNode response : selection.get(1)) {
                if (!response.isObject()) continue;
                JsonNode list = response.path("entityList");
                if (!list.isArray()) return null;
                for (JsonNode t : list) {
                    if (!t.isTextual()) return null;
                    targets.add(t.asText());
                }
            }
        }
        return new Object[] {entry, targets};
    }

    private String validatedTarget(LegalAction entry, List<String> targetIds) {
        List<String> valid = entry.validTargets(0);
        for (String t : targetIds) if (valid.contains(t)) return t;
        return null;
    }

    /** Executes a validated action. Returns true if the turn is over. */
    boolean applyPlayerAction(String pid, LegalAction entry, List<String> targetIds) {
        CardEntity card = board.card(entry.entityId());
        if (card == null) {
            LOG.warn("[Session {}] Action on unknown entity {}; re-offering.", gameId, entry.entityId());
            return false;
        }
        switch (entry.description()) {
            case ACTION_PLAY_POKEMON -> executePlayBasic(pid, card);
            case ACTION_PLAY_ENERGY -> executeAttachEnergy(pid, card, entry, targetIds);
            case ACTION_ATTACH_TOOL -> executeAttachTool(pid, card, entry, targetIds);
            case ACTION_EVOLVE -> {
                return executeEvolve(pid, card, entry, targetIds);
            }
            case ACTION_USE_TRAINER -> {
                EffectRegistry.TrainerSpec spec = registry.trainer(card.card.guid());
                CardEntity playTarget = null;
                if (spec != null && spec.targets() != null) {
                    List<String> valid = new ArrayList<>();
                    for (CardEntity t : spec.targets().targets(board, pid, card)) valid.add(t.entityId);
                    if (valid.isEmpty() || targetIds.size() > 1) return false;
                    if (!targetIds.isEmpty()) {
                        if (!valid.contains(targetIds.get(0)) || validatedTarget(entry, targetIds) == null
                                || !validatedTarget(entry, targetIds).equals(targetIds.get(0))) return false;
                        playTarget = board.card(targetIds.get(0));
                    }
                }
                return executePlayTrainer(pid, card, playTarget);
            }
            case ACTION_PLAY_STADIUM -> executePlayStadium(pid, card);
            case ACTION_USE_ABILITY -> {
                return executeUseAbility(pid, card, entry);
            }
            case ACTION_USE_ATTACK -> {
                return executeAttack(pid, card, entry);
            }
            case ACTION_RETREAT -> executeRetreat(pid, card, entry, targetIds);
            default -> LOG.warn("[Session {}] No executor for action '{}'; ignoring.", gameId, entry.description());
        }
        return false;
    }

    /** Broadcasts a play bracket, introducing the played cards to non-owners first. */
    private void sendPlaySequence(String ownerId, GameSequence name, List<?> moves, List<CardEntity> reveal) {
        List<Map<String, Object>> intros = new ArrayList<>();
        for (CardEntity c : reveal) intros.add(introduced(c));
        for (var e : players.entrySet()) {
            if (e.getKey().equals(ownerId)) {
                sendSequence(List.of(e.getValue()), name, moves);
                continue;
            }
            // Intros ride their own bracket: play executors pick the move animation from the card's TYPE.
            sendSequence(List.of(e.getValue()), GameSequence.SERIAL_SEQUENCE, intros);
            sendSequence(List.of(e.getValue()), name, moves);
        }
    }

    private boolean executePlayBasic(String pid, CardEntity card) {
        if (LegalActions.isLevelUp(card)) {
            LOG.warn("[Session {}] Refused a LV.X as a bench play ({}).", gameId, card.entityId);
            return false;
        }
        PlayArea bench = board.area(pid, "bench");
        if (bench == null || bench.children.size() >= BENCH_CAPACITY) {
            LOG.warn("[Session {}] Bench unavailable; re-offering.", gameId);
            return false;
        }
        // Lowest free SLOT, not list length: the client never renumbers bench slots.
        int position = board.freeBenchSlot(pid);
        if (!board.moveCard(card.entityId, bench.entityId)) return false;
        turn.markEnteredPlay(card.entityId);
        LOG.info("[Session {}] {} benched {}.", gameId, players.get(pid).screenName(), card);
        sendPlaySequence(pid, GameSequence.PLAY_CARD, List.of(moved(card.entityId, bench.entityId, position)), List.of(card));
        return false;
    }

    private void executeAttachEnergy(String pid, CardEntity card, LegalAction entry, List<String> targetIds) {
        String targetId = validatedTarget(entry, targetIds);
        if (targetId == null) {
            LOG.warn("[Session {}] Energy attach without a valid target ({}); re-offering.", gameId, targetIds);
            return;
        }
        CardEntity target = board.card(targetId);
        if (target == null) return;
        int position = target.children.size();
        if (!board.attachCard(card.entityId, targetId)) return;
        if (turn.energyAttached) turn.alphaGrowthTarget = null; // a 2nd attach spends the alpha Growth grant
        turn.energyAttached = true;
        turn.energyAttachesUsed++;
        card.attachedOnTurn = turn.turnNumber;
        statAdd(pid, "energyplayed", 1);
        LOG.info("[Session {}] {} attached energy {} to {}.", gameId, players.get(pid).screenName(), card, targetId);
        // PlayCard (not PlayEnergy): only executors that WrapSequence get the attach animation.
        sendPlaySequence(pid, GameSequence.PLAY_CARD, List.of(moved(card.entityId, targetId, position)), List.of(card));
    }

    private void executeAttachTool(String pid, CardEntity card, LegalAction entry, List<String> targetIds) {
        String targetId = validatedTarget(entry, targetIds);
        CardEntity target = targetId == null ? null : board.card(targetId);
        if (target == null || !LegalActions.toolSlotsFree(target)) {
            LOG.warn("[Session {}] Tool attach without a valid target ({}); re-offering.", gameId, targetIds);
            return;
        }
        int position = target.children.size();
        if (!board.attachCard(card.entityId, targetId)) return;
        LOG.info("[Session {}] {} attached tool {} to {}.", gameId, players.get(pid).screenName(), card, targetId);
        sendPlaySequence(pid, GameSequence.PLAY_CARD, List.of(moved(card.entityId, targetId, position)), List.of(card));
    }

    /** Evolves the target: the evolution takes its slot, the old stack tucks underneath. */
    private boolean executeEvolve(String pid, CardEntity card, LegalAction entry, List<String> targetIds) {
        String targetId = validatedTarget(entry, targetIds);
        CardEntity target = targetId == null ? null : board.card(targetId);
        if (target == null || target.parent == null) {
            LOG.warn("[Session {}] Evolve without a valid target ({}); re-offering.", gameId, targetIds);
            return false;
        }
        if (!performEvolution(pid, card, target)) return false;
        // Mega Evolution ends the turn unless a matching Spirit Link Tool is attached.
        return megaEvolutionEndsTurn(card);
    }

    static boolean megaEvolutionEndsTurn(CardEntity evolution) {
        if (!evolution.hasSubtype("MEGA")) return false;
        String name = evolution.card.displayName() == null ? "" : evolution.card.displayName();
        if (name.startsWith("M ")) name = name.substring(2);
        if (name.startsWith("Primal ")) name = name.substring(7);
        if (name.endsWith("-EX")) name = name.substring(0, name.length() - 3);
        name = name.strip();
        for (Entity c : evolution.children) {
            if (c instanceof CardEntity ce) {
                String dn = ce.card.displayName() == null ? "" : ce.card.displayName();
                if (dn.endsWith("Spirit Link") && dn.substring(0, dn.length() - "Spirit Link".length()).strip().equals(name)) return false;
            }
        }
        return true;
    }

    /** State + bracket core of an evolution. */
    boolean performEvolution(String pid, CardEntity card, CardEntity target) {
        Entity area = target.parent;
        if (area == null || !(area instanceof PlayArea)) return false;
        int slot = Board.benchSlotOf(target);
        // Damage counters carry through evolution.
        int damageTaken = Math.max(0, maxHp(target) - target.hp());
        List<Map<String, Object>> moves = new ArrayList<>();
        if (!board.moveCard(card.entityId, area.entityId, slot)) return false;
        moves.add(moved(card.entityId, area.entityId, slot));
        for (Entity attachment : new ArrayList<>(target.children)) {
            int position = card.children.size();
            board.attachCard(attachment.entityId, card.entityId);
            moves.add(moved(attachment.entityId, card.entityId, position));
        }
        int position = card.children.size();
        board.attachCard(target.entityId, card.entityId);
        moves.add(moved(target.entityId, card.entityId, position));
        // The counters now live on the evolution; the tucked-under pre-evolution is reset.
        target.set(Attr.HP, target.maxHp());
        if (damageTaken > 0) card.set(Attr.HP, Math.max(0, maxHp(card) - damageTaken));
        List<Object> inner = new ArrayList<>(List.of(dataEffect("From", target.entityId), dataEffect("Into", card.entityId)));
        inner.addAll(moves);
        sendPlaySequence(pid, GameSequence.EVOLVE, inner, List.of(card));
        if (damageTaken > 0) {
            for (CardEntity e : List.of(card, target)) broadcastStandalone(Msgs.hpAttribute(gameId, e, maxHp(e)));
        }
        turn.markEnteredPlay(card.entityId);
        LOG.info("[Session {}] {} evolved {} into {}.", gameId, players.get(pid).screenName(), target.entityId, card.entityId);
        // Condition markers clear on evolve.
        if (clearPokemonEffects(target)) {
            sendSequenceAll(GameSequence.REMOVE_SPECIAL_CONDITION,
                    List.of(dataEffect("Target", target.entityId), Msgs.conditionAttribute(gameId, target)));
        }
        return true;
    }

    /**
     * Wipes every effect that ends when a Pokemon leaves the Active spot or play: Special Conditions, attack and
     * retreat locks and entity-keyed history stamps. Returns true iff it had any Special Conditions.
     */
    boolean clearPokemonEffects(CardEntity pokemon) {
        boolean had = !pokemon.conditions().isEmpty();
        pokemon.setConditions(List.of());
        conditionState.clear(pokemon.entityId);
        String id = pokemon.entityId;
        turn.attackLocks.keySet().removeIf(k -> k.startsWith(id + "|"));
        turn.retreatLocks.remove(id);
        turn.damageTaken.remove(id);
        turn.damageTakenLastTurn.remove(id);
        turn.becameActiveTurn.remove(id);
        turn.healedEntities.remove(id);
        turn.healedEntitiesLastTurn.remove(id);
        turn.turnDrawEntityIds.remove(id);
        turn.turnDrawEntityIdsLastTurn.remove(id);
        return had;
    }

    // ---- trainers, stadiums, abilities -----------------------------------------------------------------------

    private void recordTrainerPlayed(CardEntity card) {
        Object name = card.get(Attr.NAME);
        String display = card.card.displayName() != null ? card.card.displayName()
                : name instanceof JsonNode j ? j.path("id").asText("") : String.valueOf(name);
        turn.trainersPlayed.add(new TurnState.TrainerPlayed(card.archetypeId, display, card.trainerType()));
    }

    /** Sends an effect's queued messages as per-viewer bracket runs. */
    void flushEffectRuns(EffectContext ctx, GameSequence defaultBracket) {
        for (var e : players.entrySet()) {
            for (var run : ctx.bracketRunsFor(e.getKey(), defaultBracket.wire())) {
                if (!run.getValue().isEmpty()) sendSequence(List.of(e.getValue()), run.getKey(), run.getValue());
            }
        }
    }

    /** "Discard N of the other cards in your hand in order to play this card": paid BEFORE the card leaves the hand. */
    private boolean payTrainerPlayCost(String pid, CardEntity card, int cost) {
        if (cost <= 0) return true;
        List<CardEntity> others = new ArrayList<>();
        for (CardEntity c : board.cardsIn(pid, "hand")) if (c != card) others.add(c);
        if (others.size() < cost) return false;
        GamePlayer player = players.get(pid);
        List<CardEntity> chosen;
        if (player.isAi()) chosen = others.subList(0, cost);
        else {
            chosen = pickEntities(pid, card.entityId, others, cost, cost, "Discard " + cost + " cards from your hand to play "
                    + card.card.displayName() + ".");
            if (chosen.size() < cost) return false;
        }
        PlayArea discard = board.area(pid, "discard");
        List<Map<String, Object>> moves = new ArrayList<>();
        for (CardEntity c : chosen) {
            int pos = discard.children.size();
            board.moveCard(c.entityId, discard.entityId);
            moves.add(moved(c.entityId, discard.entityId, pos));
        }
        sendPlaySequence(pid, GameSequence.GROUPED_MOVE, moves, chosen);
        return true;
    }

    /**
     * Plays an Item/Supporter: revealed onto activeTrainer, effect resolves, then discarded. A card with no
     * registered effect plays and does nothing (the Python `unimplemented` behaviour). True = the effect ended the turn.
     */
    boolean executePlayTrainer(String pid, CardEntity card, CardEntity playTarget) {
        PlayArea trainerArea = board.globalArea("activeTrainer");
        PlayArea discard = board.area(pid, "discard");
        if (trainerArea == null || discard == null) return false;
        EffectRegistry.TrainerSpec spec = registry.trainer(card.card.guid());
        if (!payTrainerPlayCost(pid, card, spec == null ? 0 : spec.playCost())) return false;
        if (!board.moveCard(card.entityId, trainerArea.entityId)) return false;
        card.owningPlayerId = pid; // a global-area move clears the owner
        if (card.trainerType() == Attr.TT_SUPPORTER) {
            turn.supporterPlayed = true;
            turn.supportersPlayed++;
        }
        recordTrainerPlayed(card);
        statAdd(pid, "trainersplayed", 1);
        // Stale attack sources make the client skip the reveal suppression and double-present the card.
        broadcastAttackSources(List.of());
        // Owner gets TrainerCard (instant-confirms the local drag); non-owners get PlayCard so the card is
        // presented large before it flies into the slot, where it sits while the effect resolves.
        Map<String, Object> place = moved(card.entityId, trainerArea.entityId, 0);
        List<Map<String, Object>> intros = List.of(introduced(card));
        Map<String, Object> reveal = Msgs.revealCardToAll(gameId, card.entityId, true);
        for (var e : players.entrySet()) {
            if (e.getKey().equals(pid)) {
                sendSequence(List.of(e.getValue()), GameSequence.TRAINER_CARD, List.of(place));
            } else {
                sendSequence(List.of(e.getValue()), GameSequence.SERIAL_SEQUENCE, intros);
                sendSequence(List.of(e.getValue()), GameSequence.PLAY_CARD, List.of(reveal, place));
            }
        }
        EffectContext ctx = null;
        if (spec != null && spec.effect() != null) {
            ctx = new EffectContext(this, pid, card, null, card.card.displayName(), 0, false);
            ctx.scratch.put("playTarget", playTarget);
            spec.effect().resolve(ctx);
            flushEffectRuns(ctx, GameSequence.GROUPED_MOVE);
        } else {
            LOG.info("[Session {}] Trainer {} ({}) has no scripted effect; card plays with no effect.", gameId, card.entityId,
                    card.archetypeId);
        }
        LOG.info("[Session {}] {} played trainer {}.", gameId, players.get(pid).screenName(), card);
        // Normally the trainer discards after resolving; skip when the effect relocated it.
        if (card.parent == trainerArea) {
            int position = discard.children.size();
            board.moveCard(card.entityId, discard.entityId);
            sendSequenceAll(GameSequence.TRAINER_CARD, List.of(moved(card.entityId, discard.entityId, position)));
        }
        if (ctx != null) resolveKnockouts(ctx);
        return ctx != null && ctx.endsTurn;
    }

    /** Plays a Stadium: the previous one goes to its owner's discard. */
    private void executePlayStadium(String pid, CardEntity card) {
        PlayArea stadiumArea = board.globalArea("activeStadium");
        if (stadiumArea == null) return;
        CardEntity existing = stadiumArea.children.isEmpty() ? null : (CardEntity) stadiumArea.children.get(0);
        int position = stadiumArea.children.size();
        if (!board.moveCard(card.entityId, stadiumArea.entityId)) return;
        card.owningPlayerId = pid;
        turn.stadiumPlayed = true;
        recordTrainerPlayed(card);
        statAdd(pid, "trainersplayed", 1);
        LOG.info("[Session {}] {} played stadium {}.", gameId, players.get(pid).screenName(), card);
        sendPlaySequence(pid, GameSequence.PLAY_CARD, List.of(moved(card.entityId, stadiumArea.entityId, position)), List.of(card));
        EffectRegistry.TrainerSpec spec = registry.trainer(card.card.guid());
        if (spec != null && spec.effect() != null) {
            EffectContext ctx = new EffectContext(this, pid, card, null, card.card.displayName(), 0, false);
            spec.effect().resolve(ctx);
            flushEffectRuns(ctx, GameSequence.GROUPED_MOVE);
            resolveKnockouts(ctx);
        }
        if (existing != null) {
            String owner = existing.owningPlayerId != null ? existing.owningPlayerId : pid;
            PlayArea ownerDiscard = board.area(owner, "discard");
            if (ownerDiscard != null) {
                int dp = ownerDiscard.children.size();
                board.moveCard(existing.entityId, ownerDiscard.entityId);
                sendPlaySequence(pid, GameSequence.GROUPED_MOVE, List.of(moved(existing.entityId, ownerDiscard.entityId, dp)), List.of());
            }
        }
    }

    /** Activates a registered ability; returns true when the ability ends the turn. */
    private boolean executeUseAbility(String pid, CardEntity card, LegalAction entry) {
        String id = entry.actionId();
        EffectRegistry.AbilitySpec spec = registry.ability(id);
        if (spec == null) {
            LOG.warn("[Session {}] Ability {} on {} has no registered definition; ignoring.", gameId, id, card.entityId);
            return false;
        }
        if (spec.activation() != EffectRegistry.Activation.UNLIMITED) {
            turn.usedAbilities.add(TurnState.abilityKey(card.entityId, id));
        }
        if (spec.vstar()) turn.vstarUsed.add(pid);
        String title = pieTitle(card, id);
        LOG.info("[Session {}] {} uses ability '{}'.", gameId, players.get(pid).screenName(), title);
        EffectContext ctx = new EffectContext(this, pid, card, id, title, 0, false);
        spec.effect().resolve(ctx);
        sendAbilityBrackets(ctx, card, id, title, spec.vstar());
        resolveKnockouts(ctx);
        return ctx.endsTurn || spec.endsTurn();
    }

    private static String pieTitle(CardEntity card, String abilityId) {
        for (JsonNode row : card.pieAbilities()) {
            if (abilityId.equalsIgnoreCase(row.path("abilityID").asText())) return row.path("title").path("id").asText("");
        }
        return "";
    }

    private static JsonNode pieRow(CardEntity card, String abilityId) {
        for (JsonNode row : card.pieAbilities()) {
            if (abilityId.equalsIgnoreCase(row.path("abilityID").asText())) return row;
        }
        return null;
    }

    /** Shared ability choreography: announce-only when the effect queued nothing, else Attack + PokeAbility runs. */
    private void sendAbilityBrackets(EffectContext ctx, CardEntity source, String abilityId, String title, boolean vstar) {
        Map<String, Object> head = Msgs.abilityPlayed(gameId, source.entityId, abilityId, title, "PokeAbility");
        Map<String, Object> tail = Msgs.abilityFinished(gameId, source.entityId);
        List<Object> extra = new ArrayList<>();
        if (vstar) extra.add(Msgs.vstarPowerUsed(gameId, ctx.playerId));
        if (ctx.messages().isEmpty()) {
            for (var e : players.entrySet()) {
                List<Object> inner = new ArrayList<>(List.of(head));
                inner.addAll(extra);
                inner.add(tail);
                sendSequence(List.of(e.getValue()), GameSequence.POKE_ABILITY, inner);
            }
            return;
        }
        broadcastAttackSources(List.of(source.entityId));
        List<String> targets = ctx.visualTargets.isEmpty() ? List.of(source.entityId) : ctx.visualTargets;
        for (var e : players.entrySet()) {
            List<Object> inner = new ArrayList<>(List.of(head));
            inner.addAll(extra);
            inner.add(Msgs.nonDamagingTargets(gameId, targets));
            inner.add(tail);
            sendSequence(List.of(e.getValue()), GameSequence.ATTACK, inner);
        }
        flushEffectRuns(ctx, GameSequence.GROUPED_MOVE);
    }

    /** Points the playmat's attack-source attribute at the acting entities (the Attack executor reads [0]). */
    private void broadcastAttackSources(List<String> entityIds) {
        board.playmat.set(Attr.ATTACK_SOURCES, entityIds);
        broadcastStandalone(Msgs.playlessAttackSources(gameId, board.playmat.entityId, entityIds));
    }

    // ---- retreat ------------------------------------------------------------------------------------------------

    private void executeRetreat(String pid, CardEntity card, LegalAction entry, List<String> targetIds) {
        PlayArea bench = board.area(pid, "bench"), activeArea = board.area(pid, "activePokemonArea"),
                discard = board.area(pid, "discard");
        if (bench == null || activeArea == null || discard == null) return;
        Set<String> costValid = new HashSet<>(), newActiveValid = new HashSet<>();
        for (Map<String, Object> info : entry.targetInfos()) {
            Object name = info.get("name");
            @SuppressWarnings("unchecked") List<String> valid = (List<String>) info.get("validTargets");
            if (SelectionKind.RETREAT_COST_ENTITY_LIST.wire().equals(name)) costValid.addAll(valid);
            else if (SelectionKind.RETREAT_NEW_ACTIVE.wire().equals(name)) newActiveValid.addAll(valid);
        }
        List<String> discardIds = targetIds.stream().filter(costValid::contains).toList();
        String newActiveId = targetIds.stream().filter(newActiveValid::contains).findFirst().orElse(null);
        CardEntity newActive = newActiveId == null ? null : board.card(newActiveId);
        if (newActive == null || newActive.parent != bench) {
            LOG.warn("[Session {}] Retreat without a valid new Active ({}); re-offering.", gameId, targetIds);
            return;
        }
        int cost = Math.max(0, card.getInt(Attr.RETREAT_COST, 0));
        int paid = 0;
        for (String eid : discardIds) {
            CardEntity e = board.card(eid);
            if (e != null && e.isEnergy()) paid += LegalActions.energyProvidedCount(e);
        }
        if (paid < cost) {
            LOG.warn("[Session {}] Retreat cost {} underpaid ({}) by {}; re-offering.", gameId, cost, paid, discardIds);
            return;
        }
        // The Retreat executor requires the Retreating/NewActive data effects, applies both Pokemon moves
        // data-only and WrapSequences the energy discards.
        List<Object> messages = new ArrayList<>(List.of(dataEffect("Retreating", card.entityId), dataEffect("NewActive", newActive.entityId)));
        for (String eid : discardIds) {
            int position = discard.children.size();
            if (board.moveCard(eid, discard.entityId)) messages.add(moved(eid, discard.entityId, position));
        }
        // The old active takes the new active's rendered SLOT, captured before the active move overwrites the stamp.
        int slot = Board.benchSlotOf(newActive);
        board.moveCard(newActive.entityId, activeArea.entityId);
        messages.add(moved(newActive.entityId, activeArea.entityId, 0));
        board.moveCard(card.entityId, bench.entityId, slot);
        messages.add(moved(card.entityId, bench.entityId, slot));
        turn.retreated = true;
        LOG.info("[Session {}] {} retreated {} for {} (discarded {} energy).", gameId, players.get(pid).screenName(),
                card.entityId, newActive.entityId, discardIds.size());
        sendSequenceAll(GameSequence.RETREAT, messages);
        // All effects (Special Conditions, attack locks) end when a Pokemon leaves the Active spot.
        if (clearPokemonEffects(card)) {
            sendSequenceAll(GameSequence.REMOVE_SPECIAL_CONDITION,
                    List.of(dataEffect("Target", card.entityId), Msgs.conditionAttribute(gameId, card)));
        }
        turn.retreatedEntities.add(card.entityId);
        turn.markMovedToActive(newActive.entityId);
    }

    // ---- attacks ------------------------------------------------------------------------------------------------

    /** Resolves an attack through the effect registry; attacking ends the turn. */
    private boolean executeAttack(String pid, CardEntity card, LegalAction entry) {
        String actionId = entry.actionId();
        JsonNode row = pieRow(card, actionId);
        String title = row == null ? actionId : row.path("title").path("id").asText(actionId);
        int printed = row == null ? 0 : row.path("damage").asInt(0);
        EffectRegistry.AttackSpec spec = registry.attack(actionId);
        LOG.info("[Session {}] {} attacks with '{}'.", gameId, players.get(pid).screenName(), title);
        // Confusion flip FIRST: a failed attack never happened for locks and once-per-game bookkeeping.
        if (card.conditions().contains(Attr.CONFUSED) && !resolveConfusionFlip(pid, card)) return true;

        if (title.endsWith("-GX")) turn.gxAttackUsed.add(pid);
        EffectContext ctx = new EffectContext(this, pid, card, actionId, title, printed, true);
        turn.attacksUsed.add(new TurnState.AttackUsed(card.entityId, card.archetypeId, title));
        if (spec == null || spec.effect() == null) {
            // Vanilla attacks and the `unimplemented` marker resolve their printed damage only.
            ctx.dealDamage();
        } else {
            spec.effect().resolve(ctx);
        }
        sendAttackBracket(ctx, actionId, title);
        resolveKnockouts(ctx);
        // Effects can remove the attacker itself from play.
        for (String id : new ArrayList<>(players.keySet())) {
            if (board.activePokemon(id) == null && !promoteNewActive(id)) {
                endGame(opponentId(id), players.get(id).screenName() + " has no Pokémon left");
            }
        }
        if (ctx.attackKeepsTurn) {
            LOG.info("[Session {}] Attack kept the turn going for {}.", gameId, players.get(pid).screenName());
            return false;
        }
        return true;
    }

    /** One Attack bracket per viewer: begin marker, effect messages, end marker. */
    private void sendAttackBracket(EffectContext ctx, String actionId, String title) {
        CardEntity attacker = ctx.source;
        Map<String, Object> head = Msgs.abilityPlayed(gameId, attacker.entityId, actionId, title, "Attack");
        Map<String, Object> tail = Msgs.abilityFinished(gameId, attacker.entityId);
        List<Object> extra = new ArrayList<>();
        // The Attack executor dereferences the playmat's attack-source [0].
        broadcastAttackSources(List.of(attacker.entityId));
        Map<String, Object> cleanup = null;
        // Use an orb only with a real destination; targetless attacks take the Fizzled return curve.
        if (!ctx.dealtOpponentDamage && !ctx.dealtDamage) {
            List<String> targets = !ctx.visualTargets.isEmpty() ? ctx.visualTargets : new ArrayList<>();
            if (targets.isEmpty()) for (CardEntity k : ctx.knockouts) targets.add(k.entityId);
            if (!targets.isEmpty()) extra.add(Msgs.nonDamagingTargets(gameId, targets));
            else cleanup = Msgs.cleanupAttack(gameId, attacker.entityId, "Fizzled");
        } else if (ctx.dealtDamage) {
            cleanup = Msgs.cleanupAttack(gameId, attacker.entityId, null);
        }
        for (var e : players.entrySet()) {
            // Only untagged messages (CakeAttackEffect, HP mods) ride inside the Attack bracket; tagged runs flush as
            // their own top-level brackets AFTER it, in queue order.
            List<Object> inline = new ArrayList<>();
            List<Map.Entry<String, List<Object>>> post = new ArrayList<>();
            for (var run : ctx.bracketRunsFor(e.getKey(), GameSequence.ATTACK.wire())) {
                if (run.getKey().equals(GameSequence.ATTACK.wire())) inline.addAll(run.getValue());
                else post.add(run);
            }
            List<Object> inner = new ArrayList<>();
            inner.add(head);
            inner.addAll(extra);
            inner.addAll(inline);
            if (cleanup != null) inner.add(cleanup);
            inner.add(tail);
            sendSequence(List.of(e.getValue()), GameSequence.ATTACK, inner);
            for (var run : post) if (!run.getValue().isEmpty()) sendSequence(List.of(e.getValue()), run.getKey(), run.getValue());
        }
    }

    /** Confused Pokemon flip before attacking: heads proceeds; tails hurts the attacker for 30 raw damage instead. */
    private boolean resolveConfusionFlip(String pid, CardEntity attacker) {
        int flip = rng.nextBoolean() ? 0 : 1;
        boolean heads = flip == 0;
        statAdd(pid, "headsflipped", heads ? 1 : 0);
        statAdd(pid, "tailsflipped", heads ? 0 : 1);
        sendSequenceAll(GameSequence.FLIP_FOR_CONFUSION, List.of(Msgs.coinFlip(gameId, List.of(flip), TEXT_CONFUSION_CHECK,
                heads ? TEXT_CONFUSION_PROCEEDS : TEXT_CONFUSION_HURT, attacker.entityId)));
        pause(3000);
        if (heads) return true;
        boolean ko = applyRawDamage(attacker, conditionState.confusionDamage.getOrDefault(attacker.entityId, 30),
                GameSequence.HURT_FROM_CONFUSION);
        if (ko) resolveRawKnockout(attacker);
        return false;
    }

    /** Lowers HP by {@code amount} bypassing the damage pipeline (counters, not attack damage); true if it KOs. */
    private boolean applyRawDamage(CardEntity pokemon, int amount, GameSequence bracket) {
        int remaining = Math.max(0, pokemon.hp() - amount);
        pokemon.set(Attr.HP, remaining);
        sendSequenceAll(bracket, List.of(Msgs.placeDamageEffect(gameId, pokemon.entityId, amount),
                Msgs.hpAttribute(gameId, pokemon, maxHp(pokemon))));
        return remaining <= 0;
    }

    private void resolveRawKnockout(CardEntity pokemon) {
        EffectContext ctx = new EffectContext(this, pokemon.owningPlayerId == null ? "" : pokemon.owningPlayerId, pokemon,
                null, null, 0, false);
        ctx.knockouts.add(pokemon);
        resolveKnockouts(ctx);
    }

    // ---- knockouts, prizes, promotion ---------------------------------------------------------------------------

    private static final Map<String, Integer> MULTI_PRIZE_SUBTYPES = Map.ofEntries(Map.entry("V", 2), Map.entry("VSTAR", 2),
            Map.entry("V-UNION", 3), Map.entry("VMAX", 3), Map.entry("GX", 2), Map.entry("EX", 2), Map.entry("ex", 2),
            Map.entry("TAG TEAM", 3), Map.entry("Star", 2));

    /** Prizes taken when this Pokemon is knocked out (rule-box subtypes; only Pokemon pay more than 1). */
    static int prizeValue(CardEntity pokemon) {
        if (!pokemon.isPokemon()) return 1;
        int best = 1;
        for (String s : pokemon.card.subtypes()) best = Math.max(best, MULTI_PRIZE_SUBTYPES.getOrDefault(s, 1));
        return best;
    }

    /**
     * Discards each knocked-out stack, awards prizes, promotes a new Active for the losing side and checks every win
     * condition. Triggers/passives hang off the effect registry later; the core path has none.
     */
    void resolveKnockouts(EffectContext ctx) {
        if (ctx.knockouts.isEmpty()) return;
        List<CardEntity> knocked = new ArrayList<>(ctx.knockouts);
        ctx.knockouts.clear();
        // Prize counts evaluate BEFORE any stack moves.
        Map<String, Integer> awards = new LinkedHashMap<>();
        for (CardEntity p : knocked) {
            if (p.owningPlayerId == null) continue;
            awards.merge(opponentId(p.owningPlayerId), prizeValue(p), Integer::sum);
        }
        List<String> promotions = new ArrayList<>();
        for (CardEntity pokemon : knocked) {
            String owner = pokemon.owningPlayerId;
            if (owner == null) continue;
            PlayArea discard = board.area(owner, "discard");
            if (discard == null) continue;
            boolean wasActive = board.activePokemon(owner) == pokemon;
            List<CardEntity> stack = new ArrayList<>(List.of(pokemon));
            stack.addAll(Board.stackDescendants(pokemon));
            List<Object> moves = new ArrayList<>();
            for (CardEntity entity : stack) {
                int position = discard.children.size();
                if (board.moveCard(entity.entityId, discard.entityId)) moves.add(moved(entity.entityId, discard.entityId, position));
            }
            // Every Pokemon in the stack sheds Special Conditions and locks; it has left play.
            List<Object> hpResets = new ArrayList<>();
            for (CardEntity member : stack) {
                if (!member.isPokemon()) continue;
                clearPokemonEffects(member);
                for (String k : new ArrayList<>(turn.usedAbilities)) if (k.startsWith(member.entityId + "|")) turn.usedAbilities.remove(k);
                // Restore HP to the printed max so the discarded cards carry no damage counters.
                member.set(Attr.HP, member.maxHp());
                hpResets.add(Msgs.hpAttribute(gameId, member, member.maxHp()));
            }
            if (!moves.isEmpty()) {
                // The attrs ride the Knockout bracket; a move-less bracket would NRE its executor.
                moves.addAll(hpResets);
                sendSequenceAll(GameSequence.KNOCKOUT, moves);
            }
            turn.pokemonLeftPlay.add(new TurnState.LeftPlay(pokemon.entityId, pokemon.archetypeId, owner, wasActive,
                    ctx.attack && !Objects.equals(ctx.playerId, owner) ? Boolean.TRUE : Boolean.FALSE));
            if (wasActive && !promotions.contains(owner)) promotions.add(owner);
            LOG.info("[Session {}] {} ({}) was knocked out.", gameId, pokemon.entityId, players.get(owner).screenName());
        }
        for (var e : awards.entrySet()) takePrizes(e.getKey(), e.getValue());
        for (String taker : awards.keySet()) {
            PlayArea prizes = board.area(taker, "prizePile");
            if (prizes != null && board.prizesDealt.getOrDefault(taker, 0) > 0 && prizes.children.isEmpty()) {
                endGame(taker, "Took all Prize cards");
            }
        }
        for (String owner : promotions) {
            if (!promoteNewActive(owner)) endGame(opponentId(owner), players.get(owner).screenName() + " has no Pokémon left");
        }
    }

    /** The player picks {@code count} face-down prizes and takes them to hand. */
    private List<CardEntity> takePrizes(String pid, int count) {
        PlayArea prize = board.area(pid, "prizePile"), hand = board.area(pid, "hand");
        if (prize == null || hand == null) return List.of();
        final int n = Math.min(count, prize.children.size());
        if (n <= 0) return List.of();
        GamePlayer player = players.get(pid);
        List<String> prizeIds = prize.children.stream().map(c -> c.entityId).toList();
        List<String> picked = player.isAi() ? prizeIds.subList(0, n) : promptPrizePick(pid, prizeIds, n, prize);
        List<CardEntity> cards = new ArrayList<>();
        List<Map<String, Object>> intros = new ArrayList<>(), moves = new ArrayList<>();
        for (String id : picked) {
            CardEntity card = board.card(id);
            if (card == null) continue;
            int position = hand.children.size();
            if (!board.moveCard(card.entityId, hand.entityId)) continue;
            cards.add(card);
            intros.add(introduced(card));
            moves.add(moved(card.entityId, hand.entityId, position));
        }
        if (moves.isEmpty()) return List.of();
        Map<String, Object> gap = refreshPrizeGaps(pid, prize);
        turn.prizesTaken.merge(pid, moves.size(), Integer::sum);
        statAdd(pid, "prizecardstaken", moves.size());
        LOG.info("[Session {}] {} takes {} Prize card(s).", gameId, player.screenName(), moves.size());
        // Prize faces are the taker's knowledge only: the opponent sees the face-down cards fly to hand.
        List<Map<String, Object>> publicIntros = new ArrayList<>();
        for (CardEntity c : cards) if (c.faceUp) publicIntros.add(introduced(c));
        for (var e : players.entrySet()) {
            List<Object> inner = new ArrayList<>(e.getKey().equals(pid) ? intros : publicIntros);
            inner.addAll(moves);
            inner.add(gap);
            sendSequence(List.of(e.getValue()), GameSequence.WITH_OPEN_PRIZE_CARDS, inner);
        }
        return cards;
    }

    /** Marks taken prize positions in the pile's AREA_EMPTY_SLOTS so the next pick leaves gaps at them. */
    private Map<String, Object> refreshPrizeGaps(String pid, PlayArea prize) {
        int dealt = board.prizesDealt.getOrDefault(pid, 0);
        Set<Integer> occupied = new HashSet<>();
        for (int i = 0; i < prize.children.size(); i++) {
            Entity c = prize.children.get(i);
            occupied.add(c.boardSlot != null ? c.boardSlot : i);
        }
        List<Integer> gaps = new ArrayList<>();
        for (int s = 0; s < dealt; s++) if (!occupied.contains(s)) gaps.add(s);
        prize.set(Attr.AREA_EMPTY_SLOTS, gaps);
        return Msgs.attributeModified(gameId, prize.entityId, Attr.AREA_EMPTY_SLOTS, gaps, gaps);
    }

    /** Prize-pile pick via the r.B fan-out UI; falls back to the first ones. */
    private List<String> promptPrizePick(String pid, List<String> prizeIds, int count, PlayArea prizeArea) {
        GamePlayer player = players.get(pid);
        for (int i = 0; i < MAX_SELECTION_RETRIES; i++) {
            int counter = nextCounter(pid);
            Reply reply = prompt(player, "SelectionWithTargetsRequired",
                    Msgs.prizeOffer(gameId, counter, now(), PROMPT_TAKE_PRIZE, prizeArea.entityId, prizeIds, count, count),
                    counter, null);
            if (reply.isNull()) continue;
            List<String> picked = new ArrayList<>();
            for (JsonNode response : selectionResponses(reply.selection())) {
                if (response.isObject()) for (JsonNode t : response.path("entityList")) if (prizeIds.contains(t.asText())) picked.add(t.asText());
            }
            if (picked.size() >= count) return picked.subList(0, count);
        }
        return prizeIds.subList(0, count);
    }

    /** The player promotes a benched Pokemon into the empty Active spot; false when the bench is empty (a loss). */
    boolean promoteNewActive(String pid) {
        PlayArea bench = board.area(pid, "bench"), activeArea = board.area(pid, "activePokemonArea");
        if (bench == null || activeArea == null) return false;
        List<CardEntity> candidates = board.benchPokemon(pid);
        if (candidates.isEmpty()) return false;
        GamePlayer player = players.get(pid);
        CardEntity picked = candidates.get(0);
        if (player.isAi() && candidates.size() > 1 && aiPolicy.usesFullPolicy(player)) {
            try {
                picked = aiPolicy.bestPromotion(candidates);
            } catch (RuntimeException e) {
                LOG.error("[Session {}] AI promotion choice failed; taking the first.", gameId, e);
            }
        }
        if (!player.isAi() && candidates.size() > 1) {
            Map<String, Object> offer = placementOffer(pid, PROMPT_CHOOSE_NEW_ACTIVE, candidates, TARGET_TYPE_ACTIVE);
            for (int i = 0; i < MAX_SELECTION_RETRIES; i++) {
                Reply reply = prompt(player, "SelectionWithTargetsRequired", offer, (Integer) offer.get("counter"), null);
                String id = parsePlacementReply(reply, candidates);
                if (id != null) {
                    CardEntity c = board.card(id);
                    if (c != null) picked = c;
                    break;
                }
                offer = placementOffer(pid, PROMPT_CHOOSE_NEW_ACTIVE, candidates, TARGET_TYPE_ACTIVE);
            }
        }
        board.moveCard(picked.entityId, activeArea.entityId);
        turn.markMovedToActive(picked.entityId);
        LOG.info("[Session {}] {} promoted {} to Active.", gameId, player.screenName(), picked.entityId);
        // The bare PlayActive executor lets the EntityMoved animate itself.
        sendSequenceAll(GameSequence.PLAY_ACTIVE, List.of(moved(picked.entityId, activeArea.entityId, 0)));
        return true;
    }

    // ---- between turns: Pokemon Checkup -------------------------------------------------------------------------

    /** Between-turns checkup, per player (turn order), on that player's ACTIVE only: Poison, Burn, Sleep, Paralysis cure. */
    private void runPokemonCheckup(String activeId) {
        turn.inCheckup = true;
        try {
            for (String pid : turnOrder()) {
                CardEntity active = board.activePokemon(pid);
                if (active == null) continue;
                if (active.conditions().contains(Attr.POISONED)) checkupPoison(active);
                if (active.conditions().contains(Attr.BURNED)) checkupBurn(pid, active);
                if (active.conditions().contains(Attr.ASLEEP)) checkupSleep(pid, active);
                if (pid.equals(activeId) && active.conditions().contains(Attr.PARALYZED)
                        && conditionState.paralyzedSince.getOrDefault(active.entityId, -1) < turn.turnNumber) {
                    sendSequenceAll(GameSequence.REMOVE_SPECIAL_CONDITION,
                            List.of(dataEffect("Target", active.entityId), removeSingleCondition(active, Attr.PARALYZED)));
                }
            }
        } finally {
            turn.inCheckup = false;
        }
    }

    private Map<String, Object> removeSingleCondition(CardEntity pokemon, String condition) {
        List<String> c = pokemon.conditions();
        c.remove(condition);
        pokemon.setConditions(c);
        switch (condition) {
            case Attr.ASLEEP -> conditionState.sleepCheckupCoins.remove(pokemon.entityId);
            case Attr.PARALYZED -> conditionState.paralyzedSince.remove(pokemon.entityId);
            case Attr.POISONED -> conditionState.poisonCounters.remove(pokemon.entityId);
            case Attr.CONFUSED -> conditionState.confusionDamage.remove(pokemon.entityId);
            default -> { }
        }
        return Msgs.conditionAttribute(gameId, pokemon);
    }

    private void checkupPoison(CardEntity active) {
        int counters = Math.max(0, conditionState.poisonCounters.getOrDefault(active.entityId, 1));
        boolean ko = applyRawDamage(active, 10 * counters, GameSequence.POISON_DAMAGE);
        pause(1500);
        if (ko) resolveRawKnockout(active);
    }

    /** Burn tick: 2 damage counters, then a flip: heads cures. */
    private void checkupBurn(String pid, CardEntity active) {
        boolean ko = applyRawDamage(active, 20, GameSequence.BURN_DAMAGE);
        if (ko) {
            pause(1500);
            resolveRawKnockout(active);
            return;
        }
        int flip = rng.nextBoolean() ? 0 : 1;
        boolean heads = flip == 0;
        statAdd(pid, "headsflipped", heads ? 1 : 0);
        statAdd(pid, "tailsflipped", heads ? 0 : 1);
        sendSequenceAll(GameSequence.FLIP_FOR_BURN, List.of(Msgs.coinFlip(gameId, List.of(flip), TEXT_BURN_CHECK,
                heads ? TEXT_BURN_CURED : TEXT_STILL_BURNED, active.entityId)));
        pause(3000);
        if (heads) {
            sendSequenceAll(GameSequence.REMOVE_SPECIAL_CONDITION,
                    List.of(dataEffect("Target", active.entityId), removeSingleCondition(active, Attr.BURNED)));
        }
    }

    /** Sleep flip: all heads wakes. */
    private void checkupSleep(String pid, CardEntity active) {
        int coins = Math.max(1, conditionState.sleepCheckupCoins.getOrDefault(active.entityId, 1));
        List<Integer> flips = new ArrayList<>();
        for (int i = 0; i < coins; i++) flips.add(rng.nextBoolean() ? 0 : 1);
        boolean woke = flips.stream().allMatch(f -> f == 0);
        statAdd(pid, "headsflipped", (int) flips.stream().filter(f -> f == 0).count());
        statAdd(pid, "tailsflipped", (int) flips.stream().filter(f -> f != 0).count());
        sendSequenceAll(GameSequence.FLIP_TO_WAKE_UP, List.of(Msgs.coinFlip(gameId, flips, TEXT_SLEEP_CHECK,
                woke ? TEXT_WOKE_UP : TEXT_STILL_ASLEEP, active.entityId)));
        pause(3000);
        if (woke) {
            sendSequenceAll(GameSequence.REMOVE_SPECIAL_CONDITION,
                    List.of(dataEffect("Target", active.entityId), removeSingleCondition(active, Attr.ASLEEP)));
        }
        LOG.info("[Session {}] Sleep check for {}: {} ({}).", gameId, active.entityId, woke ? "woke up" : "still Asleep", flips);
    }

    // ---- AI turns -----------------------------------------------------------------------------------------------

    private void runAiTurn(String activeId) {
        aiPause(0); // the turn-start draw animation finishes before the first move
        GamePlayer player = players.get(activeId);
        if (aiPolicy.policyName(player, settings).equals("basic")) {
            runBasicAiTurn(activeId);
        } else {
            boolean[] attacked = new boolean[1];
            try {
                runSmartAiTurn(activeId, attacked);
            } catch (GameOverException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.error("[Session {}] Smart AI turn failed; {}.", gameId, attacked[0] ? "ending turn" : "falling back to basic policy", e);
                if (!attacked[0]) runBasicAiTurn(activeId);
            }
        }
        aiPause(800); // last animation settles before the human's clock starts
    }

    private void runSmartAiTurn(String activeId, boolean[] attacked) {
        GamePlayer player = players.get(activeId);
        boolean full = aiPolicy.usesFullPolicy(player);
        Set<String> tried = new HashSet<>();
        for (int i = 0; i < MAX_ACTIONS_PER_TURN; i++) {
            AiPolicy.Choice choice = aiPolicy.nextAction(board, turn, activeId, gameId, registry, tried);
            if (choice == null) break;
            tried.add(choice.action().key());
            LOG.info("[Session {}] {} (AI) {} -> {}.", gameId, player.screenName(), choice.action().description(), choice.targets());
            boolean[] over = new boolean[1];
            runUnit(() -> over[0] = applyPlayerAction(activeId, choice.action(), choice.targets()));
            if (over[0]) return;
            aiPause(0);
        }
        LegalAction attack = aiPolicy.attackAction(board, turn, activeId, gameId, registry, full);
        if (attack == null) {
            // The Active can't pay for anything: swap in a benched Pokemon that can.
            AiPolicy.Choice retreat = aiPolicy.retreatAction(board, turn, activeId, gameId, registry);
            if (retreat != null) {
                LOG.info("[Session {}] {} (AI) retreats -> {}.", gameId, player.screenName(), retreat.targets());
                runUnit(() -> applyPlayerAction(activeId, retreat.action(), retreat.targets()));
                aiPause(0);
                attack = aiPolicy.attackAction(board, turn, activeId, gameId, registry, full);
            }
        }
        if (attack == null) {
            LOG.info("[Session {}] {} (AI) ends turn.", gameId, player.screenName());
            return;
        }
        attacked[0] = true;
        LOG.info("[Session {}] {} (AI) attacks with {}.", gameId, player.screenName(), attack.actionId());
        aiPause(0); // let the last played card settle before the attack starts
        LegalAction a = attack;
        runUnit(() -> applyPlayerAction(activeId, a, List.of()));
        aiPause(1000); // attack/knockout animation before the turn ends
    }

    /** The original policy: one Energy, then an affordable attack. */
    private void runBasicAiTurn(String activeId) {
        GamePlayer player = players.get(activeId);
        List<LegalAction> legal = LegalActions.compute(board, turn, activeId, gameId, registry);
        LegalAction energy = legal.stream().filter(a -> a.description().equals(ACTION_PLAY_ENERGY)).findFirst().orElse(null);
        if (energy != null && !energy.validTargets(0).isEmpty()) {
            String t = energy.validTargets(0).get(0);
            runUnit(() -> applyPlayerAction(activeId, energy, List.of(t)));
            aiPause(0);
        }
        LegalAction attack = LegalActions.compute(board, turn, activeId, gameId, registry).stream()
                .filter(a -> a.description().equals(ACTION_USE_ATTACK)).findFirst().orElse(null);
        if (attack == null) {
            LOG.info("[Session {}] {} (AI) ends turn.", gameId, player.screenName());
            return;
        }
        runUnit(() -> applyPlayerAction(activeId, attack, List.of()));
        aiPause(1000);
    }

    // ---- end of game ------------------------------------------------------------------------------------------

    private final java.util.concurrent.atomic.AtomicBoolean ending = new java.util.concurrent.atomic.AtomicBoolean();

    private Object[] mvpFor(String pid) {
        Map<String, Object[]> byCard = mvpDamage.get(pid);
        if (byCard == null || byCard.isEmpty()) return null;
        Map.Entry<String, Object[]> best = null;
        for (var e : byCard.entrySet()) if (best == null || (Integer) e.getValue()[0] > (Integer) best.getValue()[0]) best = e;
        return new Object[] {best.getKey(), best.getValue()[1]};
    }

    /**
     * Per-viewer GameCompletedMessage additionalParameters. "GameResult" is REQUIRED (the summary dialog indexes it
     * unguarded and compares against "Win"); the me_/opp_ stat keys and GameDuration are optional.
     */
    Map<String, Object> eogAdditionalParameters(String viewerId, String winnerId, String reason) {
        boolean won = viewerId.equals(winnerId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("GameResult", Msgs.text(won ? "Win" : "Loss"));
        p.put("VictoryReason", Msgs.text(reason));
        p.put("playmat.endgame.stat.gameresult", Msgs.text(players.get(winnerId).screenName() + " won the match (" + reason + ")."));
        p.put("GameDuration", Msgs.text(String.valueOf(System.currentTimeMillis() - matchStartedAt)));
        String[][] sides = {{"me", viewerId}, {"opp", opponentId(viewerId)}};
        for (String[] side : sides) {
            Map<String, Integer> st = stats.getOrDefault(side[1], Map.of());
            for (String key : EOG_STAT_KEYS) {
                p.put(side[0] + "_playmat.endgame.stat." + key, Msgs.text(String.valueOf(st.getOrDefault(key, 0))));
            }
            // Summary-page MVP card render (the client reads the $...$ key literally).
            Object[] mvp = mvpFor(side[1]);
            if (mvp != null) {
                p.put(side[0] + "_$playmat.endgame.stat.mvp.archetypeid$", Msgs.text((String) mvp[0]));
                p.put(side[0] + "_playmat.endgame.stat.mvp", Msgs.text((String) mvp[1]));
            }
        }
        Map<String, Integer> vs = stats.getOrDefault(viewerId, Map.of());
        p.put("Damagedealt", Msgs.text(String.valueOf(vs.getOrDefault("damagedealt", 0))));
        p.put("Headsflipped", Msgs.text(String.valueOf(vs.getOrDefault("headsflipped", 0))));
        return p;
    }

    private Map<String, Object> coinReward(int coins) {
        return obj("name", "GameReward", "rewardType", "Tokens", "rewardAmount", coins,
                "rewardProductID", EMPTY_SEQUENCE_ID, "rewardCurrency", "Tokens",
                // rewardDescription must be non-null: the client dereferences its ID unguarded.
                "rewardDescription", Msgs.text(ENDGAME_REWARD_TEXT), "rewardReason", "GameCompleted",
                "selectedFrom", List.of(), "selectedIndex", 0, "rewardSource", "", "index", 0, "openedReward", null);
    }

    /**
     * Announces the result on both clients (GameCompletedMessage, coins, ladder points, quest credit) and unwinds the
     * game loop by throwing {@link GameOverException}. Idempotent: a second caller just unwinds.
     */
    void endGame(String winnerId, String reason) {
        if (!ending.compareAndSet(false, true)) throw new GameOverException();
        String loserId = opponentId(winnerId);
        LOG.info("[Session {}] Game over: {} wins ({}).", gameId, players.get(winnerId).screenName(), reason);
        turnsAtEnd = turn.turnNumber;
        for (GamePlayer p : uniqueRecipients(allPlayers())) {
            String pid = p.accountId;
            boolean won = pid.equals(winnerId);
            int coins = won ? COINS_PER_WIN : COINS_PER_LOSS;
            if (p instanceof NetworkPlayer) {
                try {
                    hooks.persistResult(pid, coins, won);
                } catch (RuntimeException e) {
                    LOG.error("[Session {}] persisting the result for {} failed", gameId, pid, e);
                }
            }
            List<Object> rewards = new ArrayList<>();
            if (coins > 0) rewards.add(coinReward(coins));
            rewards.addAll(progressDailyChallenges(pid, p, won, reason));
            Map<String, Object> completed = obj("gameID", gameId, "winner", winnerId, "loser", loserId,
                    "endOfGameText", Msgs.text(reason), "coins", coins, "exp", 0, "share", false, "rewardList", rewards,
                    "additionalParameters", eogAdditionalParameters(pid, winnerId, reason));
            wire.lock();
            try {
                p.send("SequenceMessage", Msgs.standalone(gameId, Msgs.msg("GameCompletedMessage", completed)), 0);
            } finally {
                wire.unlock();
            }
        }
        pushWalletAndAccountUpdates();
        result = new GameResult(winnerId, loserId, reason);
        phase = GamePhase.GAME_OVER;
        synchronized (stateLock) {
            resumed = true;
            stateLock.notifyAll();
        }
        throw new GameOverException();
    }

    /** Credits multiplayer results before the client's end-game quest animation. */
    private List<Object> progressDailyChallenges(String pid, GamePlayer p, boolean won, String reason) {
        if (!(p instanceof NetworkPlayer) || solo) return List.of();
        if (networkPlayers().values().stream().map(n -> n.accountId).distinct().count() < 2) return List.of();
        if (reason.equals("A game error occurred.") || turnsAtEnd == 0) return List.of();
        try {
            MatchHooks.QuestCredit r = hooks.creditQuests(pid, gameId, stats.getOrDefault(pid, Map.of()), won, matchStartedAt / 1000.0);
            if (r == null) return List.of();
            boolean any = r.completedQuestsAndXpTotal() != null && r.completedQuestsAndXpTotal().size() > 0
                    || r.progressedQuests() != null && r.progressedQuests().size() > 0;
            if (any) {
                Map<String, Object> payload = obj("completedQuestsAndXPTotal", r.completedQuestsAndXpTotal(),
                        "progressedQuests", r.progressedQuests());
                wire.lock();
                try {
                    p.send("SequenceMessage", Msgs.standalone(gameId, Msgs.msg("QuestsProgressed", payload)), 0);
                } finally {
                    wire.unlock();
                }
            }
            return new ArrayList<>(r.rewards());
        } catch (RuntimeException e) {
            LOG.error("[Session {}] Daily challenge progress failed for {}", gameId, pid, e);
            return List.of();
        }
    }

    private void pushWalletAndAccountUpdates() {
        Set<String> pushed = new HashSet<>();
        for (GamePlayer p : players.values()) {
            if (!(p instanceof NetworkPlayer) || !pushed.add(p.accountId)) continue;
            try {
                Map<String, Object> wallet = hooks.walletPayload(p.accountId);
                if (wallet != null) p.send("CurrentWallet", wallet, 0);
                Map<String, Object> account = hooks.accountPayload(p.accountId);
                if (account != null) p.send("AccountUpdated", obj("account", account), 0);
            } catch (RuntimeException e) {
                LOG.error("[Session {}] account refresh failed for {}", gameId, p.accountId, e);
            }
        }
    }

    /** Ends the game with the conceding player as loser (runs on the network thread; the loop unwinds on its own). */
    public void concede(String accountId) {
        if (phase == GamePhase.GAME_OVER || !players.containsKey(accountId)) return;
        GamePlayer loser = players.get(accountId);
        try {
            endGame(opponentId(accountId), loser.screenName() + " conceded");
        } catch (GameOverException ignored) {
            // end_game's unwind belongs to the gameplay thread
        }
        for (GamePlayer p : players.values()) {
            CompletableFuture<JsonNode> f = p.pendingChoice;
            if (f != null && !f.isDone()) f.completeExceptionally(new GameOverException());
        }
        if (gameplayThread == null) close();
    }

    // ---- disconnect / reconnect ----------------------------------------------------------------------------------

    private void cancelGrace(String accountId) {
        Thread t = graceTimers.remove(accountId);
        if (t != null) t.interrupt();
    }

    private void armGrace(String accountId) {
        cancelGrace(accountId);
        graceTimers.put(accountId, Thread.ofVirtual().start(() -> disconnectGrace(accountId)));
    }

    /** Awards the game to the connected opponent if the player never returns. */
    private void disconnectGrace(String accountId) {
        try {
            Thread.sleep(Math.round(settings.reconnectGraceSeconds * 1000));
        } catch (InterruptedException e) {
            return;
        }
        boolean pending;
        synchronized (stateLock) {
            graceTimers.remove(accountId);
            pending = disconnected.contains(accountId) || reconnecting.contains(accountId);
        }
        if (!pending || phase == GamePhase.GAME_OVER) return;
        LOG.info("[Session {}] Reconnect grace expired for {}; resolving game.", gameId, accountId);
        GamePlayer opponent = players.get(opponentId(accountId));
        if (opponent instanceof NetworkPlayer n && n.connected()) {
            concede(accountId); // concede on the absent player's behalf: the opponent wins
        } else {
            close(); // no connected human to award (both gone / AI opponent): tear down
        }
    }

    /** Detaches a player's socket without ending the game; starts the grace timer. Stale teardown is ignored. */
    public void onPlayerDisconnect(String accountId, ClientContext from) {
        GamePlayer player = players.get(accountId);
        if (!(player instanceof NetworkPlayer n) || phase == GamePhase.GAME_OVER) return;
        int epoch;
        synchronized (stateLock) {
            if (from != null && n.client() != from) {
                LOG.info("[Session {}] Ignoring stale disconnect for {}.", gameId, accountId);
                return;
            }
            if (n.client() == null && disconnected.contains(accountId)) return;
            if (resumed) {
                resyncPlayers.clear();
                resyncReady.clear();
                offersToRestore.clear();
                restoringOffers.clear();
            }
            if (!stateDispatched) readyPlayers.remove(accountId);
            LOG.info("[Session {}] Player {} disconnected - detaching (grace {}s).", gameId, accountId, settings.reconnectGraceSeconds);
            if (n.pendingOffer != null && n.pendingChoice != null && !n.pendingChoice.isDone()) offersToRestore.add(accountId);
            n.bind(null);
            reconnecting.remove(accountId);
            resumed = false;
            disconnected.add(accountId);
            epoch = ++resyncEpoch;
            resyncPlayers.add(accountId);
            resyncReady.remove(accountId);
            restoringOffers.remove(accountId);
            armGrace(accountId);
            stateLock.notifyAll();
        }
        Thread.ofVirtual().start(() -> showConnectionPause(accountId, epoch));
    }

    /** Ends live offers of the other players and shows a non-modal reconnect banner. */
    private void showConnectionPause(String subjectId, int epoch) {
        if (!stateDispatched) return;
        awaitStableState();
        boolean forceEnded = false;
        List<String> forced = new ArrayList<>();
        wire.lock();
        try {
            if (phase == GamePhase.GAME_OVER || epoch != resyncEpoch || !disconnected.contains(subjectId)) return;
            for (var e : networkPlayers().entrySet()) {
                NetworkPlayer viewer = e.getValue();
                if (e.getKey().equals(subjectId) || !viewer.connected()) continue;
                CompletableFuture<JsonNode> f = viewer.pendingChoice;
                if (viewer.pendingOffer != null && f != null && !f.isDone()) {
                    viewer.send("ForceSelectionFinished", obj(), 0);
                    forced.add(e.getKey());
                    forceEnded = true;
                }
            }
        } finally {
            wire.unlock();
        }
        // (lock order is stateLock -> wire everywhere, so the restore list is updated after the wire is released)
        synchronized (stateLock) {
            offersToRestore.addAll(forced);
        }
        // The client's force-finish command ends by wiping the prompt override the banner sets.
        if (forceEnded) pause(1500);
        wire.lock();
        try {
            if (phase == GamePhase.GAME_OVER || epoch != resyncEpoch || !disconnected.contains(subjectId)) return;
            for (var e : networkPlayers().entrySet()) {
                if (e.getKey().equals(subjectId) || !e.getValue().connected()) continue;
                e.getValue().send("SequenceMessage", Msgs.standalone(gameId, Msgs.pauseOnPrompt(gameId,
                        "Opponent disconnected. Waiting up to " + (int) settings.reconnectGraceSeconds
                                + " seconds for them to reconnect.")), 0);
            }
        } finally {
            wire.unlock();
        }
    }

    /** Rebinds one socket and starts that client's native scene rebuild (MatchFound with Reconnecting). */
    public void reconnectPlayer(ClientContext client, String accountId) {
        GamePlayer player = players.get(accountId);
        if (!(player instanceof NetworkPlayer n)) return;
        synchronized (stateLock) {
            if (n.client() == client && !reconnecting.contains(accountId)) {
                LOG.info("[Session {}] Ignoring duplicate reconnect for {}.", gameId, accountId);
                return;
            }
            if (n.client() == client && reconnecting.contains(accountId)) return;
            LOG.info("[Session {}] Player {} reconnecting.", gameId, accountId);
            reconnecting.remove(accountId);
            resyncReady.remove(accountId);
            restoringOffers.remove(accountId);
            n.bind(client);
            disconnected.remove(accountId);
            resumed = false;
            resyncPlayers.add(accountId);
            reconnecting.add(accountId);
            armGrace(accountId);
        }
        wire.lock();
        try {
            n.send("MatchFound", matchFoundPayload(true), 0);
        } finally {
            wire.unlock();
        }
    }

    /** Installs one reconnect snapshot and resumes after all returnees load. */
    private void resendStateTo(String accountId) {
        GamePlayer player = players.get(accountId);
        if (!(player instanceof NetworkPlayer n) || !n.connected()) return;
        synchronized (stateLock) {
            if (!reconnecting.contains(accountId)) return;
        }
        ClientContext handler = n.client();
        awaitStableState();
        if (n.client() != handler) return;
        sendSerializedGameState(accountId);
        pause(500);
        synchronized (stateLock) {
            if (n.client() != handler || !reconnecting.contains(accountId)) return;
            Set<String> otherReturnees = new HashSet<>(reconnecting);
            otherReturnees.remove(accountId);
            if (!disconnected.isEmpty() || !otherReturnees.isEmpty()) {
                String text = !disconnected.isEmpty() ? "Opponent disconnected. Waiting up to "
                        + (int) settings.reconnectGraceSeconds + " seconds for them to reconnect."
                        : "Synchronizing the other player. Please wait.";
                sendPausePrompt(n, text, false);
            }
            reconnecting.remove(accountId);
            cancelGrace(accountId);
            resyncReady.add(accountId);
            if (!resyncPlayers.isEmpty() && resyncReady.containsAll(resyncPlayers) && reconnecting.isEmpty() && disconnected.isEmpty()) {
                resumeReconnectCycle();
            }
        }
    }

    /** Clears wait banners and restores only unresolved real offers (called with stateLock held). */
    private void resumeReconnectCycle() {
        wire.lock();
        try {
            for (var e : networkPlayers().entrySet()) {
                NetworkPlayer p = e.getValue();
                if (!p.connected()) return;
                p.send("SequenceMessage", Msgs.standalone(gameId, Msgs.closePauseOnPrompt(gameId)), 0);
                GamePlayer.Offer offer = p.pendingOffer;
                CompletableFuture<JsonNode> f = p.pendingChoice;
                if (offersToRestore.contains(e.getKey()) && offer != null && f != null && !f.isDone()) {
                    restoringOffers.add(e.getKey());
                    p.send(offer.name(), replayValue(offer.value()), offer.flags());
                    LOG.info("[Session {}] Restored pending offer for {}.", gameId, e.getKey());
                }
                String text = pausePrompts.get(e.getKey());
                if (text != null) p.send("SequenceMessage", Msgs.standalone(gameId, Msgs.pauseOnPrompt(gameId, text)), 0);
            }
        } finally {
            wire.unlock();
        }
        resyncPlayers.clear();
        resyncReady.clear();
        offersToRestore.clear();
        resumed = true;
        restoringOffers.clear();
        stateLock.notifyAll();
        LOG.info("[Session {}] Reconnect epoch {} resumed play.", gameId, resyncEpoch);
    }
}
