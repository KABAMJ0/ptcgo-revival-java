package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.net.WargCodec;
import com.ptcgo.revival.net.WargFlags;
import java.io.DataInputStream;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandshakeIntegrationTest {
    private record Reply(int requestId, int flags, JsonNode json) {}

    private static Reply roundTrip(Socket s, String name, int rid, int flags) throws Exception {
        s.getOutputStream().write(WargCodec.frameMessage(name, Map.of(), rid, flags));
        s.getOutputStream().flush();
        DataInputStream in = new DataInputStream(s.getInputStream());
        byte[] header = new byte[WargCodec.HEADER_SIZE];
        in.readFully(header);
        int[] h = WargCodec.decodeHeader(header);
        byte[] body = new byte[h[0]];
        in.readFully(body);
        return new Reply(h[1], h[2], WargCodec.parseJson(WargCodec.inflateIfNeeded(body, h[2], 1 << 20)));
    }

    @Test
    void handshakeFlow() throws Exception {
        Config cfg = new Config();
        PacketRouter router = new PacketRouter(List.of(new HandshakeHandler()));
        try (GameServer server = new GameServer(cfg, router, null)) {
            int port = server.start(0);
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(5000);
                Reply r = roundTrip(s, "RequestConnectionServiceWithVersion", 1, 0);
                assertEquals(1, r.requestId());
                assertEquals("ConnectionService", r.json().get("name").asText());
                assertTrue(r.json().get("value").get("connectionEndPoint").asText().startsWith("127.0.0.1:"));

                r = roundTrip(s, "RequestSession", 2, 0);
                assertEquals(WargFlags.GRANTED_SESSION, r.flags());
                String session = r.json().get("value").get("session").asText();
                assertDoesNotThrow(() -> java.util.UUID.fromString(session));

                r = roundTrip(s, "RequestLogin", 3, 0);
                assertEquals("gas", r.json().get("value").get("validAuthTypes").get(0).asText());

                r = roundTrip(s, "Ping", 4, WargFlags.PING_PONG);
                assertEquals("Pong", r.json().get("name").asText());
                assertEquals(WargFlags.PING_PONG, r.flags());
            }
        }
    }
}
