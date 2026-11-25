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

class DataSyncIntegrationTest {
    private static JsonNode read(DataInputStream in) throws Exception {
        byte[] header = new byte[WargCodec.HEADER_SIZE];
        in.readFully(header);
        int[] h = WargCodec.decodeHeader(header);
        byte[] body = new byte[h[0]];
        in.readFully(body);
        return WargCodec.parseJson(WargCodec.inflateIfNeeded(body, h[2], 1 << 20));
    }

    private static void send(Socket s, String name, Object payload, int rid) throws Exception {
        byte[] body = WargCodec.JSON.writeValueAsBytes(Map.of("name", name, "value", payload));
        s.getOutputStream().write(WargCodec.frame(body, rid, 0));
        s.getOutputStream().flush();
    }

    @Test
    void walletDecksAndSettings() throws Exception {
        Services services = TestServices.create(new Config());
        PacketRouter router = new PacketRouter(List.of(new AuthHandler(), new DataSyncHandler()));
        try (GameServer server = new GameServer(services, router, null);
             Socket s = new Socket("127.0.0.1", server.start(0))) {
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(s, "AuthenticateGASAuthToken", Map.of("userID", "ash", "token", "pw"), 1);
            for (int i = 0; i < 12; i++) read(in); // login burst

            send(s, "GetWallet", Map.of(), 2);
            JsonNode wallet = read(in);
            assertEquals("CurrentWallet", wallet.get("name").asText());
            assertEquals(1100, wallet.get("value").get("currencies").get(0).get("value").asInt(), "1000 starting tokens + the 100-token first daily login reward");

            // New deck: nil id gets a fresh guid, cosmetic defaults and VALID_FORMATS are added.
            send(s, "CakeSaveDeck", Map.of("deck", Map.of("deckID", DataSyncHandler.NIL_GUID, "deckName", "Mine",
                    "piles", Map.of("Main", List.of()), "attributes", List.of(
                            Map.of("name", 200670, "value", "not-owned-coin")))), 3);
            JsonNode saved = read(in);
            assertEquals("DeckSaved", saved.get("name").asText());
            String id = saved.get("value").get("deckID").asText();
            assertNotEquals(DataSyncHandler.NIL_GUID, id);
            JsonNode attrs = saved.get("value").get("deck").get("attributes");
            boolean coinReset = false, hasFormats = false;
            for (JsonNode a : attrs) {
                if (a.get("name").asInt() == 200670) coinReset = DataSyncHandler.DEFAULT_COIN.equals(a.get("value").asText());
                if (a.get("name").asInt() == 10860) hasFormats = a.get("value").get(0).asText().equals("Unlimited");
            }
            assertTrue(coinReset, "unowned coin must fall back to the default");
            assertTrue(hasFormats);

            send(s, "GetDeckList", Map.of(), 4);
            JsonNode list = read(in);
            assertEquals("OnlineDecksFound", list.get("name").asText());
            assertEquals(id, list.get("value").get("decks").get(0).get("deckID").asText());
            assertEquals("DecksValidated", read(in).get("name").asText());

            send(s, "CakeDeleteDeck", Map.of("deckID", id), 5);
            assertEquals("DeckDeleted", read(in).get("name").asText());
            send(s, "GetDeckList", Map.of(), 6);
            assertEquals(0, read(in).get("value").get("decks").size());
            read(in);

            send(s, "SetAccountSettings", Map.of("settings", Map.of("7", 3)), 7);
            JsonNode upd = read(in);
            assertEquals("AccountUpdated", upd.get("name").asText());
            assertEquals(3, upd.get("value").get("account").get("attributes").get(0).get("value").get("7").asInt());

            send(s, "GetCollectionCount", Map.of(), 8);
            assertEquals(0, read(in).get("value").get("collectionCountList").size());
        }
    }

    @Test
    void unauthenticatedRequestsAreHarmless() throws Exception {
        Services services = TestServices.create(new Config());
        PacketRouter router = new PacketRouter(List.of(new DataSyncHandler()));
        try (GameServer server = new GameServer(services, router, null);
             Socket s = new Socket("127.0.0.1", server.start(0))) {
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(s, "GetWallet", Map.of(), 1);
            assertEquals(0, read(in).get("value").get("currencies").get(0).get("value").asInt());
            send(s, "CakeSaveDeck", Map.of("deck", Map.of("deckName", "x")), 2); // ignored
            send(s, "GetCollectionCount", Map.of(), 3);
            assertEquals("CollectionCountFound", read(in).get("name").asText());
        }
    }
}
