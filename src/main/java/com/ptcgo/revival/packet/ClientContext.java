package com.ptcgo.revival.packet;

import com.ptcgo.revival.Config;

import java.net.InetSocketAddress;
import java.util.Map;

/** What a handler may do with a connected client. */
public interface ClientContext {
    Config config();

    String sessionId();

    InetSocketAddress remoteAddress();

    InetSocketAddress localAddress();

    /** Queue one JSON message ({"name","value"} envelope) for this client. */
    void send(String messageName, Map<String, ?> payload, int requestId, int flags);

    default void send(String messageName, Map<String, ?> payload, int requestId) {
        send(messageName, payload, requestId, 0);
    }
}
