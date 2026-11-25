package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.auth.CasTickets;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.db.Account;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.deck.DeckValidator;
import com.ptcgo.revival.net.ClientRegistry;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A ClientContext that records what a handler sends, for handler unit tests. */
final class FakeClient implements ClientContext {
    record Sent(String name, JsonNode value, int requestId) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    final List<Sent> sent = new ArrayList<>();
    private final Services services;
    private Account account;
    private final ClientState state = new ClientState();

    FakeClient(Database db, Catalog catalog) {
        this.services = new Services(new Config(), new AccountRepository(db), new PlayerRepository(db), new CasTickets(),
                new ClientRegistry(), catalog, DeckValidator.PERMISSIVE,
                new com.ptcgo.revival.progression.Progression(new com.ptcgo.revival.db.QuestRepository(db),
                        new com.ptcgo.revival.db.VersusRepository(db), com.ptcgo.revival.progression.Seasons.loadBundled(),
                        new com.ptcgo.revival.db.DailyLoginRepository(db, com.ptcgo.revival.progression.DailyRewards.loadBundled(),
                                catalog, java.time.Instant::now, new java.util.Random(1))),
                null);
    }

    FakeClient login(Account a) {
        this.account = a;
        return this;
    }

    JsonNode value(int i) {
        return sent.get(i).value();
    }

    List<String> names() {
        return sent.stream().map(Sent::name).toList();
    }

    Sent first(String name) {
        return sent.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no " + name + " in " + names()));
    }

    @Override public Services services() { return services; }
    @Override public String sessionId() { return "test"; }
    @Override public InetSocketAddress remoteAddress() { return new InetSocketAddress("127.0.0.1", 1); }
    @Override public InetSocketAddress localAddress() { return new InetSocketAddress("127.0.0.1", 2); }
    @Override public Account account() { return account; }
    @Override public void authenticated(Account account) { this.account = account; }

    @Override
    public void send(String messageName, Map<String, ?> payload, int requestId, int flags) {
        sent.add(new Sent(messageName, JSON.valueToTree(payload), requestId));
    }

    @Override public void sendRaw(byte[] body, int requestId, int flags) { throw new UnsupportedOperationException(); }
    @Override public ClientState state() { return state; }
    @Override public void close() {}
}
