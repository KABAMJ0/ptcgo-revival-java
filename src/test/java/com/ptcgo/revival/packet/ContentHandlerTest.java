package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.db.DynamicPageRepository;
import com.ptcgo.revival.db.SocialRepository;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.net.WargCodec;
import com.ptcgo.revival.net.WargFlags;
import java.io.DataInputStream;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContentHandlerTest {
    private static JsonNode read(DataInputStream in, int[] flagsOut) throws Exception {
        byte[] header = new byte[WargCodec.HEADER_SIZE];
        in.readFully(header);
        int[] h = WargCodec.decodeHeader(header);
        byte[] body = new byte[h[0]];
        in.readFully(body);
        flagsOut[0] = h[2];
        return h[2] == WargFlags.PROTOBUF ? null : WargCodec.parseJson(WargCodec.inflateIfNeeded(body, h[2], 1 << 20));
    }

    private static void send(Socket s, String name, Object payload, int rid) throws Exception {
        s.getOutputStream().write(WargCodec.frame(WargCodec.JSON.writeValueAsBytes(Map.of("name", name, "value", payload)), rid, 0));
        s.getOutputStream().flush();
    }

    @Test
    void pagesNotificationsAchievementsAndScenarios() throws Exception {
        Services services = TestServices.create(new Config());
        var pages = new DynamicPageRepository(services.players().database());
        long future = System.currentTimeMillis() + 86_400_000L;
        var m = WargCodec.JSON;
        pages.insert("landing", 2, m.readTree("{\"template\":\"B\",\"sortOrder\":99,\"startTime\":0,\"endTime\":0}"), true);
        pages.insert("landing", 1, m.readTree("{\"template\":\"A\",\"sortOrder\":99,\"startTime\":0,\"endTime\":" + (future * 2) + "}"), true);
        pages.insert("landing", 3, m.readTree("{\"template\":\"Future\",\"startTime\":" + future + ",\"endTime\":0}"), true);
        pages.insert("landing", 4, m.readTree("{\"template\":\"Off\"}"), false);
        pages.insert("maintenance", 5, m.readTree("{\"template\":\"Maint\",\"startTime\":" + future + ",\"endTime\":0}"), true);

        PacketRouter router = new PacketRouter(List.of(new AuthHandler(), new ContentHandler()));
        try (GameServer server = new GameServer(services, router, null);
             Socket s = new Socket("127.0.0.1", server.start(0))) {
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            int[] fl = new int[1];
            send(s, "AuthenticateGASAuthToken", Map.of("userID", "ash", "token", "pw"), 1);
            for (int i = 0; i < 12; i++) read(in, fl);

            send(s, "GetDynamicPages", Map.of(), 2);
            JsonNode res = read(in, fl).get("value");
            JsonNode landing = res.get("pageData");
            assertEquals(2, landing.size(), "future and disabled pages are not shown");
            assertEquals("A", landing.get(0).get("template").asText());
            assertEquals(1, landing.get(0).get("sortOrder").asInt(), "the column wins over the embedded sortOrder");
            assertEquals("B", landing.get(1).get("template").asText());
            assertEquals(1, res.get("maintenanceData").size(), "maintenance notices are never time-filtered");

            // a pending invitation shows up as a notification
            var accounts = services.accounts();
            var misty = accounts.create("Misty", "pw", false).orElseThrow();
            var ash = accounts.findByUsername("ash").orElseThrow();
            new SocialRepository(services.players().database()).upsert(misty.accountId(), ash.accountId(), 0);
            send(s, "GetNotifications", Map.of(), 3);
            JsonNode n = read(in, fl).get("value").get("notificationList");
            assertEquals(1, n.size());
            assertEquals(0, n.get(0).get("notificationType").asInt());
            JsonNode payload = m.readTree(n.get(0).get("payload").asText());
            assertEquals("FriendInvitation", payload.get("name").asText());
            assertEquals(misty.accountId(), payload.get("value").get("friend").get("accountID").asText());
            assertEquals("Misty", payload.get("value").get("friend").get("displayName").asText());

            send(s, "GetAchievements", Map.of(), 4);
            assertEquals(0, read(in, fl).get("value").get("achievements").size());

            send(s, "GetProtobufScenarios", Map.of(), 5);
            read(in, fl);
            assertEquals(WargFlags.PROTOBUF, fl[0]);
        }
    }
}
