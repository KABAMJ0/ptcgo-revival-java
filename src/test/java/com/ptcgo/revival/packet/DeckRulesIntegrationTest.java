package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.catalog.Catalog.Card;
import com.ptcgo.revival.deck.FormatManager;
import com.ptcgo.revival.deck.GameFormat;
import com.ptcgo.revival.deck.RulesDeckValidator;
import com.ptcgo.revival.deck.ThemeDecks;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.net.WargCodec;
import java.io.DataInputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeckRulesIntegrationTest {
    private static final String SET = "SV5";

    private static Card card(String guid, String name, int type, Integer stage, String... subtypes) {
        ObjectNode wire = WargCodec.JSON.createObjectNode();
        wire.putObject("10020").put("type", "string").put("value", SET);
        wire.putObject("200300").put("type", "int").put("value", type);
        if (stage != null) wire.putObject("200540").put("type", "int").put("value", stage);
        return new Card(guid, SET, name, SET, 1, "x", List.of(subtypes), List.of(), wire);
    }

    private static JsonNode read(DataInputStream in) throws Exception {
        byte[] header = new byte[WargCodec.HEADER_SIZE];
        in.readFully(header);
        int[] h = WargCodec.decodeHeader(header);
        byte[] body = new byte[h[0]];
        in.readFully(body);
        return WargCodec.parseJson(WargCodec.inflateIfNeeded(body, h[2], 1 << 20));
    }

    private static void send(Socket s, String name, Object payload, int rid) throws Exception {
        s.getOutputStream().write(WargCodec.frame(
                WargCodec.JSON.writeValueAsBytes(Map.of("name", name, "value", payload)), rid, 0));
        s.getOutputStream().flush();
    }

    private static Map<String, Object> deck(String id, List<String> guids) {
        return Map.of("deckID", id, "deckName", "D", "piles", Map.of("deck", guids), "attributes", List.of());
    }

    private static boolean rowValid(JsonNode rows, String formatName) {
        for (JsonNode r : rows) if (r.get("formatName").asText().equals(formatName)) return r.get("valid").asBoolean();
        throw new AssertionError("no row for " + formatName + " in " + rows);
    }

    @Test
    void savedDecksAreValidatedAgainstRealRules() throws Exception {
        List<Card> cards = new ArrayList<>();
        cards.add(card("p1", "Pikachu", 0, 0, "Basic"));
        for (int i = 0; i < 14; i++) cards.add(card("t" + i, "Trainer " + i, 2, null, "Item"));
        Catalog catalog = Catalog.of(cards);
        FormatManager fm = new FormatManager(List.of(
                GameFormat.of("Standard", FormatManager.STANDARD, "Modified", Set.of(SET), false),
                GameFormat.of("Unlimited", FormatManager.UNLIMITED, "Unlimited", Set.of(), true)),
                catalog, ThemeDecks.none(), g -> false, () -> 0L);
        Services base = TestServices.create(new Config());
        Services services = new Services(base.config(), base.accounts(), base.players(), base.tickets(), base.registry(),
                catalog, new RulesDeckValidator(catalog, fm, ThemeDecks.none()), base.progression(), null);

        List<String> legal = new ArrayList<>(List.of("p1", "p1", "p1", "p1"));
        for (int i = 0; i < 14; i++) for (int n = 0; n < 4; n++) legal.add("t" + i); // 4 Pikachu + 56 trainers
        assertEquals(60, legal.size());

        PacketRouter router = new PacketRouter(List.of(new AuthHandler(), new DataSyncHandler()));
        try (GameServer server = new GameServer(services, router, null);
             Socket s = new Socket("127.0.0.1", server.start(0))) {
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(s, "AuthenticateGASAuthToken", Map.of("userID", "ash", "token", "pw"), 1);
            for (int i = 0; i < 12; i++) read(in);
            Map<String, Integer> grants = new HashMap<>();
            for (Card c : cards) grants.put(c.guid(), 4);
            services.players().addToCollection(services.registry().find(
                    services.accounts().findByUsername("ash").orElseThrow().accountId()).orElseThrow().account().accountId(), grants, true);

            send(s, "CakeSaveDeck", Map.of("deck", deck("00000000-0000-0000-0000-000000000000", legal)), 2);
            JsonNode saved = read(in).get("value");
            assertTrue(rowValid(saved.get("validationResults"), "Modified"));
            assertTrue(rowValid(saved.get("validationResults"), "Unlimited"));
            for (JsonNode r : saved.get("validationResults")) assertNotEquals("TrainerChallenge", r.get("formatName").asText());
            String legalId = saved.get("deckID").asText();

            // 59 cards: still saves (work in progress) but is invalid everywhere.
            send(s, "CakeSaveDeck", Map.of("deck", deck("short-1", legal.subList(0, 59))), 3);
            JsonNode shortSaved = read(in).get("value");
            assertEquals("short-1", shortSaved.get("deckID").asText());
            assertFalse(rowValid(shortSaved.get("validationResults"), "Modified"));
            assertFalse(rowValid(shortSaved.get("validationResults"), "Unlimited"));

            // Deck list: valid-format attribute reflects the rules; TrainerChallenge never reaches the client.
            send(s, "GetDeckList", Map.of(), 4);
            JsonNode decks = read(in).get("value").get("decks");
            read(in); // DecksValidated
            for (JsonNode d : decks) {
                JsonNode formats = null;
                for (JsonNode a : d.get("attributes")) if (a.get("name").asInt() == 10860) formats = a.get("value");
                assertNotNull(formats);
                boolean isLegal = d.get("deckID").asText().equals(legalId);
                assertEquals(isLegal, formats.toString().contains("Modified"));
                assertFalse(formats.toString().contains("TrainerChallenge"));
            }

            // ValidateDecks (unsaved contents) honours the requested format list.
            send(s, "ValidateDecks", Map.of("decks", List.of(deck("x", legal)), "formats", List.of(FormatManager.STANDARD)), 5);
            JsonNode rows = read(in).get("value").get("results");
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).get("valid").asBoolean());

            send(s, "ValidateAllDecks", Map.of("formats", List.of(FormatManager.UNLIMITED)), 6);
            JsonNode all = read(in).get("value").get("results");
            assertEquals(2, all.size()); // both saved decks, one row each
        }
    }
}
