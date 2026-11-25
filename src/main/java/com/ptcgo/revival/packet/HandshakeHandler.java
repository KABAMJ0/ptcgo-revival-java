package com.ptcgo.revival.packet;

import com.ptcgo.revival.net.WargFlags;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/** Connection bootstrap: service redirect, session grant, auth-type offer, ping/pong. */
public final class HandshakeHandler implements PacketHandler {
    @Override
    public Map<String, MessageHandler> handlers() {
        return Map.of(
                "RequestConnectionServiceWithVersion", HandshakeHandler::connectionService,
                "RequestSession", HandshakeHandler::requestSession,
                "RequestLogin", HandshakeHandler::requestLogin,
                "Ping", HandshakeHandler::ping);
    }

    private static void connectionService(ClientContext c, com.fasterxml.jackson.databind.JsonNode v, int rid, int f) {
        c.send("ConnectionService",
                Map.of("connectionEndPoint", endpointHost(c) + ":" + c.config().tcpPort), rid);
    }

    /** Private/loopback clients reconnect to the address they dialled (no NAT hairpin needed). */
    static String endpointHost(ClientContext c) {
        InetAddress remote = c.remoteAddress().getAddress();
        if (remote.isSiteLocalAddress() || remote.isLoopbackAddress() || remote.isLinkLocalAddress()) {
            return c.localAddress().getAddress().getHostAddress();
        }
        return c.config().publicHost;
    }

    private static void requestSession(ClientContext c, com.fasterxml.jackson.databind.JsonNode v, int rid, int f) {
        c.send("GrantedSession", Map.of(
                "version", c.config().gameVersion,
                "serverTime", 1620000000000L,
                "options", Map.of(),
                "session", c.sessionId()), rid, WargFlags.GRANTED_SESSION);
    }

    private static void requestLogin(ClientContext c, com.fasterxml.jackson.databind.JsonNode v, int rid, int f) {
        c.send("RequestedAuthType", Map.of("validAuthTypes", List.of("gas")), rid);
    }

    private static void ping(ClientContext c, com.fasterxml.jackson.databind.JsonNode v, int rid, int f) {
        c.send("Pong", Map.of(), rid, WargFlags.PING_PONG);
    }
}
