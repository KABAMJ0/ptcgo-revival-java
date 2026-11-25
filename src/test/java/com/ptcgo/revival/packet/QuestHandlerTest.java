package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.net.WargCodec;
import java.io.DataInputStream;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestHandlerTest {
    private static JsonNode read(DataInputStream in) throws Exception {
        byte[] header = new byte[WargCodec.HEADER_SIZE];
        in.readFully(header);
        int[] h = WargCodec.decodeHeader(header);
        byte[] body = new byte[h[0]];
        in.readFully(body);
        return WargCodec.parseJson(WargCodec.inflateIfNeeded(body, h[2], 1 << 20));
    }

    private static void send(Socket s, String name, Object payload, int rid) throws Exception {
        s.getOutputStream().write(WargCodec.frame(WargCodec.JSON.writeValueAsBytes(Map.of("name", name, "value", payload)), rid, 0));
        s.getOutputStream().flush();
    }

    @Test
    void questFlowOverTheWire() throws Exception {
        Services services = TestServices.create(new Config());
        PacketRouter router = new PacketRouter(List.of(new AuthHandler(), new QuestHandler()));
        try (GameServer server = new GameServer(services, router, null);
             Socket s = new Socket("127.0.0.1", server.start(0))) {
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(s, "AuthenticateGASAuthToken", Map.of("userID", "ash", "token", "pw"), 1);
            JsonNode ok = read(in);
            boolean seenSeason = false, seenQuestConfig = false;
            for (int i = 0; i < 10; i++) {
                String n = read(in).get("name").asText();
                seenSeason |= n.equals("CurrentVersusSeason");
                seenQuestConfig |= n.equals("QuestConfigurationUpdated");
            }
            assertTrue(seenSeason && seenQuestConfig);
            JsonNode attrs = ok.get("value").get("account").get("attributes");
            assertEquals(15, attrs.size());
            boolean xpIsObject = false;
            for (JsonNode a : attrs) if (a.get("name").asInt() == 201790) xpIsObject = a.get("value").has("Colorless");
            assertTrue(xpIsObject, "affinity xp is a {affinity: xp} object");

            send(s, "GetQuests", Map.of(), 2);
            assertEquals("QuestConfigurationUpdated", read(in).get("name").asText());
            JsonNode all = read(in);
            assertEquals("AllQuests", all.get("name").asText());
            JsonNode avail = all.get("value").get("availableQuests");
            assertEquals(2, avail.size());
            String id = avail.get(0).get("questDefinition").get("questID").asText();

            send(s, "QuestsSelected", Map.of("quests", List.of(id)), 3);
            read(in);
            assertEquals(1, read(in).get("value").get("currentQuests").size());

            send(s, "QuestsEnabled", Map.of("enabled", false), 4);
            JsonNode upd = read(in);
            assertEquals("AccountUpdated", upd.get("name").asText());
            read(in);
            assertEquals(0, read(in).get("value").get("currentQuests").size(), "disabled quests are hidden");

            send(s, "QuestsSelected", Map.of("quests", List.of(1, 2)), 5); // non-strings are ignored
            read(in);
            read(in);
        }
    }
}
