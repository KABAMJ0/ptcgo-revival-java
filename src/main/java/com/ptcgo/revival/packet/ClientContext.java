package com.ptcgo.revival.packet;

import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.db.Account;

import java.net.InetSocketAddress;
import java.util.Map;

/** What a handler may do with a connected client. */
public interface ClientContext {
    Services services();

    default Config config() {
        return services().config();
    }

    String sessionId();

    InetSocketAddress remoteAddress();

    InetSocketAddress localAddress();

    /** The logged-in account, or null before authentication. */
    Account account();

    /** Called by the auth handler: binds the account, registers the client and relaxes the idle timeout. */
    void authenticated(Account account);

    /** Queue one JSON message ({"name","value"} envelope) for this client. */
    void send(String messageName, Map<String, ?> payload, int requestId, int flags);

    default void send(String messageName, Map<String, ?> payload, int requestId) {
        send(messageName, payload, requestId, 0);
    }

    /** Send an already-encoded body (e.g. a protobuf frame); it is compressed if {@code flags} says so. */
    void sendRaw(byte[] body, int requestId, int flags);

    /** Per-connection sync bookkeeping. */
    ClientState state();

    void close();
}
