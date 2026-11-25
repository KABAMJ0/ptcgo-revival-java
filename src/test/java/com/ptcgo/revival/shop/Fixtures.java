package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.catalog.Catalog.Card;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.db.Database;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Hand-built catalogue, products and database for the shop tests. */
public final class Fixtures {
    public static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {}

    public static Database db() throws Exception {
        var tmp = Files.createTempFile("ptcgo-shop", ".db");
        tmp.toFile().deleteOnExit();
        Database db = new Database("jdbc:sqlite:" + tmp);
        db.migrate();
        return db;
    }

    public static String account(Database db, String name) throws Exception {
        return new AccountRepository(db).create(name, "pw", false).orElseThrow().accountId();
    }

    /** A card of {@code set} with the given rarity and type (3 = energy), distinct guid per (set, number). */
    public static Card card(String set, int number, String name, int rarity, int type, boolean special) {
        ObjectNode wire = JSON.createObjectNode();
        wire.set("200550", typed("int", rarity));
        wire.set("200300", typed("int", type));
        if (special) wire.set("200970", JSON.createObjectNode().put("type", "bool").put("value", true));
        String guid = String.format("%08x-0000-4000-8000-%012x", set.hashCode(), number);
        return new Card(guid, set, name, set, number, "pokemon", List.of(), List.of(), wire);
    }

    public static ObjectNode typed(String type, int v) {
        return JSON.createObjectNode().put("type", type).put("value", v);
    }

    /** A set with 6 commons, 5 uncommons, 3 rares, 2 rare holos, 1 ultra rare, 1 secret and 2 basic energies. */
    public static List<Card> smallSet(String set) {
        List<Card> out = new ArrayList<>();
        int n = 1;
        for (int i = 0; i < 6; i++) out.add(card(set, n++, "C" + i, 0, 0, false));
        for (int i = 0; i < 5; i++) out.add(card(set, n++, "U" + i, 1, 0, false));
        for (int i = 0; i < 3; i++) out.add(card(set, n++, "R" + i, 2, 0, false));
        for (int i = 0; i < 2; i++) out.add(card(set, n++, "H" + i, 3, 0, false));
        out.add(card(set, n++, "Ultra", 16, 0, false));
        out.add(card(set, n++, "Secret", 17, 0, false));
        out.add(card(set, n++, "Fire Energy", 0, 3, false));
        out.add(card(set, n++, "Water Energy", 0, 3, false));
        return out;
    }

    public static ShopProduct pack(String guid, String set) {
        return product("""
                {"guid":"%s","key":"%s","kind":"booster_pack","product_type":21,"name":"%s pack","prices":[],
                 "attributes":{"10020":{"type":"string","value":"%s"},"10140":{"type":"json","value":"{\\"id\\": \\"%s pack\\"}"},
                 "10540":{"type":"int","value":21},"10570":{"type":"string","value":"%s"},"10510":{"type":"string","value":"%s_img"}}}
                """.formatted(guid, set, set, set, set, guid, set));
    }

    public static ShopProduct product(String json) {
        try {
            return ShopProducts.parse(JSON.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ShopProduct coin(String guid, String name) {
        return product("""
                {"guid":"%s","key":"core","kind":"coins","product_type":5,"name":"%s","prices":[],
                 "attributes":{"10140":{"type":"json","value":"{\\"id\\": \\"%s\\"}"},"10540":{"type":"int","value":5}}}
                """.formatted(guid, name, name));
    }

    public static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
