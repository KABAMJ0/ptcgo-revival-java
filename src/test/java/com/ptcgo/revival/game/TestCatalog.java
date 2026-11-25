package com.ptcgo.revival.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.Catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A small synthetic catalogue (real attribute shapes) so engine tests need no exported card data. */
public final class TestCatalog {
    private static final ObjectMapper M = new ObjectMapper();
    public static final String ZAP = "11111111-0000-4000-8000-000000000001";
    public static final String ZAPPER = "11111111-0000-4000-8000-000000000002";
    public static final String SPARK_ENERGY = "11111111-0000-4000-8000-000000000003";
    public static final String POTION = "11111111-0000-4000-8000-000000000004";
    public static final String PROF = "11111111-0000-4000-8000-000000000005";
    public static final String ARENA = "11111111-0000-4000-8000-000000000006";
    public static final String BURNER = "11111111-0000-4000-8000-000000000007";
    public static final String GNAW = "aaaaaaaa-0000-4000-8000-000000000001";
    public static final String SPARK = "aaaaaaaa-0000-4000-8000-000000000002";
    public static final String BIG = "aaaaaaaa-0000-4000-8000-000000000003";
    public static final String SCORCH = "aaaaaaaa-0000-4000-8000-000000000004";
    public static final String EX_BODY = "11111111-0000-4000-8000-000000000008";
    public static final String SLAM = "aaaaaaaa-0000-4000-8000-000000000005";

    private TestCatalog() {}

    private static void put(ObjectNode n, int id, String type, Object v) {
        ObjectNode a = n.putObject(String.valueOf(id));
        a.put("type", type);
        if (v instanceof Integer i) a.put("value", i);
        else a.put("value", String.valueOf(v));
    }

    private static ObjectNode base(String guid, String name, int cardType) {
        ObjectNode n = M.createObjectNode();
        put(n, 10140, "json", "{\"id\": \"" + name + "\"}");
        put(n, 10510, "string", "001");
        put(n, 200000, "string", guid);
        put(n, 200300, "int", cardType);
        put(n, 200580, "string", "TEST");
        put(n, 200630, "string", name);
        return n;
    }

    private static String attack(String id, String title, String cost, int damage) {
        return "{\"abilityType\": \"Attack\", \"title\": {\"id\": \"" + title + "\"}, \"gameText\": {\"id\": \"\"}, "
                + "\"abilityID\": \"" + id + "\", \"cost\": " + cost + ", \"damage\": " + damage
                + ", \"amountOperator\": \"\", \"sortOrder\": 0}";
    }

    private static Catalog.Card pokemon(String guid, String name, int hp, int stage, String from, int type, int weak,
                                        String attacks, List<String> subtypes) {
        ObjectNode n = base(guid, name, 0);
        put(n, 200490, "int", hp);
        put(n, 200540, "int", stage);
        put(n, 200570, "json", "[" + type + "]");
        put(n, 200590, "json", "[" + weak + "]");
        put(n, 200600, "int", -1);
        put(n, 200660, "string", "x");
        put(n, 200800, "int", 1);
        put(n, 200820, "int", 2);
        if (from != null) put(n, 200640, "string", from);
        put(n, 200740, "json", "[" + attacks + "]");
        return new Catalog.Card(guid, "TEST", name, "TEST", 1, "pokemon", subtypes, List.of(), n);
    }

    public static Catalog catalog() {
        List<Catalog.Card> cards = new ArrayList<>();
        cards.add(pokemon(ZAP, "Zap", 60, 0, null, 8, 6,
                attack(GNAW, "Gnaw", "{\"Colorless\": 1}", 10) + "," + attack(SPARK, "Spark", "{\"Lightning\": 1, \"Colorless\": 1}", 30),
                List.of("Basic")));
        cards.add(pokemon(ZAPPER, "Zapper", 90, 1, "Zap", 8, 6, attack(BIG, "Big Bolt", "{\"Lightning\": 2, \"Colorless\": 1}", 60),
                List.of("Stage 1")));
        cards.add(pokemon(BURNER, "Burner", 50, 0, null, 6, 11,
                attack(SCORCH, "Scorch", "{\"Fire\": 1}", 20), List.of("Basic")));
        cards.add(pokemon(EX_BODY, "Zap ex", 120, 0, null, 8, 6, attack(SLAM, "Slam", "{\"Lightning\": 1}", 40),
                List.of("Basic", "ex")));
        ObjectNode e = base(SPARK_ENERGY, "Lightning Energy", 3);
        put(e, 200570, "json", "[8]");
        put(e, 201040, "json", "{\"options\": [[8]]}");
        cards.add(new Catalog.Card(SPARK_ENERGY, "TEST", "Lightning Energy", "TEST", 2, "energy", List.of("Basic"), List.of(), e));
        for (Object[] t : new Object[][] {{POTION, "Potion", 0}, {PROF, "Professor Test", 2}, {ARENA, "Test Arena", 1}}) {
            ObjectNode n = base((String) t[0], (String) t[1], 2);
            put(n, 200270, "int", (Integer) t[2]);
            cards.add(new Catalog.Card((String) t[0], "TEST", (String) t[1], "TEST", 3, "trainer", List.of(), List.of(), n));
        }
        return Catalog.of(cards);
    }

    /** A 60-card deck: counts per guid. */
    public static JsonNode deck(Map<String, Integer> counts) {
        ObjectNode d = M.createObjectNode();
        d.put("deckID", "deck-test");
        d.put("deckName", "Test");
        List<String> list = new ArrayList<>();
        counts.forEach((g, c) -> {
            for (int i = 0; i < c; i++) list.add(g);
        });
        var piles = d.putObject("piles");
        var arr = piles.putArray("deck");
        list.forEach(arr::add);
        d.putArray("attributes");
        return d;
    }

    /** Lightning deck: Zap (Basic), Zapper (evolves from Zap), Lightning Energy, a few trainers. */
    public static JsonNode lightningDeck() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put(ZAP, 12);
        m.put(ZAPPER, 4);
        m.put(EX_BODY, 2);
        m.put(SPARK_ENERGY, 28);
        m.put(POTION, 6);
        m.put(PROF, 6);
        m.put(ARENA, 2);
        return deck(m);
    }
}
