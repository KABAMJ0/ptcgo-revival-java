package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.net.WargCodec;
import java.io.DataInputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthIntegrationTest {
    private static JsonNode read(DataInputStream in) throws Exception {
        byte[] header = new byte[WargCodec.HEADER_SIZE];
        in.readFully(header);
        int[] h = WargCodec.decodeHeader(header);
        byte[] body = new byte[h[0]];
        in.readFully(body);
        return WargCodec.parseJson(WargCodec.inflateIfNeeded(body, h[2], 1 << 20));
    }

    private static void send(Socket s, String name, Map<String, ?> payload, int rid) throws Exception {
        s.getOutputStream().write(WargCodec.frameMessage(name, payload, rid, 0));
        s.getOutputStream().flush();
    }

    private static List<String> loginSequence(DataInputStream in) throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 8; i++) names.add(read(in).get("name").asText());
        return names;
    }

    @Test
    void loginFlow() throws Exception {
        Services services = TestServices.create(new Config());
        PacketRouter router = new PacketRouter(List.of(new HandshakeHandler(), new AuthHandler()));
        try (GameServer server = new GameServer(services, router, null)) {
            int port = server.start(0);
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "StartAuthentication", Map.of("authType", "GAS"), 1);
                assertEquals("RequestAuthToken", read(in).get("name").asText());

                // First login auto-registers.
                send(s, "AuthenticateGASAuthToken", Map.of("userID", "Ash", "token", "pika"), 2);
                JsonNode ok = read(in);
                assertEquals("AuthenticationSuccessful", ok.get("name").asText());
                assertEquals("Ash", ok.get("value").get("account").get("username").asText());
                assertEquals(15, ok.get("value").get("account").get("attributes").size());
                List<String> rest = new ArrayList<>();
                for (int i = 0; i < 10; i++) rest.add(read(in).get("name").asText());
                assertEquals(List.of("EulaSuccessful", "CohortsForAccount", "PlayerNotInGame",
                        "NetworkStatusIndicatorConfiguration", "DefaultTrainerChallengeDeckMessage",
                        "CurrentVersusSeason", "CurrentDailyRewardTrack", "QuestConfigurationUpdated",
                        "AccountPropertiesUpdated", "DynamicVersions"), rest);
                assertEquals(1, services.registry().onlineCount());

                // Same account, second connection: refused while the first is online.
                try (Socket s2 = new Socket("127.0.0.1", port)) {
                    s2.setSoTimeout(5000);
                    send(s2, "AuthenticateGASAuthToken", Map.of("userID", "ash", "token", "pika"), 3);
                    JsonNode fail = read(new DataInputStream(s2.getInputStream()));
                    assertEquals("AuthenticationFailed", fail.get("name").asText());
                    assertEquals(AuthHandler.ALREADY_ONLINE, fail.get("value").get("reason").get("token").asText());
                }
            }
            // Wrong password on a fresh connection.
            Thread.sleep(200);
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(5000);
                send(s, "AuthenticateGASAuthToken", Map.of("userID", "Ash", "token", "wrong"), 4);
                JsonNode fail = read(new DataInputStream(s.getInputStream()));
                assertTrue(fail.get("value").get("reason").get("token").asText().startsWith("Incorrect password"));
            }
        }
    }

    @Test
    void guestLoginRefusedByDefault() throws Exception {
        Services services = TestServices.create(new Config());
        PacketRouter router = new PacketRouter(List.of(new AuthHandler()));
        try (GameServer server = new GameServer(services, router, null);
             Socket s = new Socket("127.0.0.1", server.start(0))) {
            s.setSoTimeout(5000);
            send(s, "StartAuthentication", Map.of("authType", "DeviceID"), 1);
            JsonNode fail = read(new DataInputStream(s.getInputStream()));
            assertEquals(AuthHandler.GUESTS_DISABLED, fail.get("value").get("reason").get("token").asText());
        }
    }
}
