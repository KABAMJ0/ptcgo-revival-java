package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.net.WargCodec;
import com.ptcgo.revival.social.FriendMatchStarter;
import com.ptcgo.revival.social.GameChatRouter;
import com.ptcgo.revival.social.SocialModule;

import java.io.DataInputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared harness for the social / trade integration tests: one server, any number of logged-in sockets. */
final class SocialTestSupport implements AutoCloseable {
    final Services services;
    final SocialModule social;
    final GameServer server;
    final int port;

    SocialTestSupport(FriendMatchStarter starter, GameChatRouter gameChat) throws Exception {
        services = TestServices.create(new Config());
        social = SocialModule.create(services, starter, gameChat);
        List<PacketHandler> groups = new ArrayList<>();
        groups.add(new AuthHandler());
        groups.addAll(social.handlers());
        groups.add(new DataSyncHandler());
        groups.add(hooks());
        server = new GameServer(services, new PacketRouter(groups), null);
        port = server.start(0);
    }

    /** Stands in for the AuthHandler / ClientConnection wiring, which this change does not edit. */
    private static PacketHandler hooks() {
        return () -> {
            Map<String, PacketHandler.MessageHandler> m = new HashMap<>();
            m.put("HookLogin", (c, v, rid, f) -> SocialModule.onLogin(c));
            m.put("HookDisconnectNow", (c, v, rid, f) -> SocialModule.of(c).disconnectNow(c));
            m.put("HookDisconnectAsync", (c, v, rid, f) -> SocialModule.onDisconnect(c));
            return m;
        };
    }

    Client login(String user) throws Exception {
        Client c = new Client(new Socket("127.0.0.1", port));
        c.send("AuthenticateGASAuthToken", Map.of("userID", user, "token", "pw"), 1);
        JsonNode ok = c.next();
        if (!"AuthenticationSuccessful".equals(ok.get("name").asText())) throw new IllegalStateException(ok.toString());
        c.accountId = ok.get("value").get("account").get("accountID").asText();
        c.name = user;
        for (int i = 0; i < 10; i++) c.next(); // rest of the login burst
        return c;
    }

    @Override
    public void close() throws Exception {
        server.close();
    }

    static final class Client implements AutoCloseable {
        final Socket socket;
        final DataInputStream in;
        String accountId;
        String name;
        int lastRequestId;

        Client(Socket s) throws Exception {
            socket = s;
            s.setSoTimeout(5000);
            in = new DataInputStream(s.getInputStream());
        }

        void send(String name, Object payload, int rid) throws Exception {
            socket.getOutputStream().write(WargCodec.frame(
                    WargCodec.JSON.writeValueAsBytes(Map.of("name", name, "value", payload)), rid, 0));
            socket.getOutputStream().flush();
        }

        JsonNode next() throws Exception {
            byte[] header = new byte[WargCodec.HEADER_SIZE];
            in.readFully(header);
            int[] h = WargCodec.decodeHeader(header);
            byte[] body = new byte[h[0]];
            in.readFully(body);
            lastRequestId = h[1];
            return WargCodec.parseJson(WargCodec.inflateIfNeeded(body, h[2], 1 << 20));
        }

        /** Next message, asserted to have this name; returns its value. */
        JsonNode expect(String name) throws Exception {
            JsonNode m = next();
            if (!name.equals(m.path("name").asText())) {
                throw new AssertionError("expected " + name + " but got " + m);
            }
            return m.get("value");
        }

        /** True if nothing arrives within a short window. */
        boolean quiet() throws Exception {
            socket.setSoTimeout(300);
            try {
                next();
                return false;
            } catch (SocketTimeoutException e) {
                return true;
            } finally {
                socket.setSoTimeout(5000);
            }
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
