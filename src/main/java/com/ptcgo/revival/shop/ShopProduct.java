package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A product (pack, bundle, deck, coin, sleeve, deck box). Mirrors ptcgo/game/models/product.py.
 * {@code attributes} maps "<attrId>" to the typed {"type","value"} node. Immutable: pricing and SKU aliasing
 * return copies.
 */
public record ShopProduct(String guid, String key, Kind kind, int productType, Map<String, JsonNode> attributes,
                          List<Price> prices, List<String> grants, int tokens, boolean oncePerAccount,
                          List<String> contents, boolean themeDeck, boolean themeLegal, String deckBox) {
    public enum Kind { PACK, BUNDLE, DECK, OTHER }

    public record Price(long name, long value) {}

    public static final int ATTR_SET_KEY = 10020;
    public static final int ATTR_NAME = 10140;
    public static final int ATTR_SET_NUMBER = 10190;
    public static final int ATTR_IMAGE_URL = 10510;
    public static final int ATTR_PRODUCT_TYPE = 10540;
    public static final int ATTR_CATALOG_ID = 10570;
    public static final int ATTR_ARCHETYPE_ID = 10000;

    public static final int TYPE_COINS = 5;
    public static final int TYPE_DECK_BOX = 11;
    public static final int TYPE_DECKS = 12;
    public static final int TYPE_PACKS = 21;
    public static final int TYPE_SLEEVE = 27;

    static final UUID SKU_NAMESPACE = Uuid5.of(Uuid5.NAMESPACE_DNS, "spiritptcgo.shop.sku");
    private static final ObjectMapper JSON = new ObjectMapper();

    public ShopProduct {
        attributes = attributes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        prices = prices == null ? List.of() : List.copyOf(prices);
        grants = grants == null ? List.of() : List.copyOf(grants);
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    public boolean isBundle() {
        return kind == Kind.BUNDLE;
    }

    public boolean isDeck() {
        return kind == Kind.DECK;
    }

    public boolean isPack() {
        return kind == Kind.PACK;
    }

    /** Raw attribute value; "json" typed strings are parsed (as Python's get_attribute_value does). */
    public JsonNode attribute(int id) {
        JsonNode a = attributes.get(String.valueOf(id));
        if (a == null) return null;
        JsonNode v = a.get("value");
        if (v == null || v.isNull()) return null;
        if ("json".equals(a.path("type").asText()) && v.isTextual()) {
            try {
                return JSON.readTree(v.asText());
            } catch (Exception e) {
                return v;
            }
        }
        return v;
    }

    /** Display name: attribute 10140's {"id": ...}, else the plain text. */
    public String name() {
        JsonNode n = attribute(ATTR_NAME);
        if (n == null) return "";
        if (n.isObject()) return n.path("id").asText("");
        return n.asText("");
    }

    public ShopProduct withPrices(List<Price> newPrices) {
        return new ShopProduct(guid, key, kind, productType, attributes, newPrices, grants, tokens, oncePerAccount,
                contents, themeDeck, themeLegal, deckBox);
    }

    public ShopProduct withAttribute(int id, String type, JsonNode value) {
        Map<String, JsonNode> copy = new LinkedHashMap<>(attributes);
        ObjectNode a = JsonNodeFactory.instance.objectNode();
        a.put("type", type);
        a.set("value", value);
        copy.put(String.valueOf(id), a);
        return new ShopProduct(guid, key, kind, productType, copy, prices, grants, tokens, oncePerAccount, contents,
                themeDeck, themeLegal, deckBox);
    }

    /** Shop-facing alias with its own GUID, so the client's eviction on shop exit never touches the owned GUID. */
    public static String skuGuid(String productGuid) {
        return Uuid5.of(SKU_NAMESPACE, productGuid).toString();
    }

    public ShopProduct asSku() {
        String sku = skuGuid(guid);
        JsonNode v = JsonNodeFactory.instance.textNode(sku);
        ShopProduct p = withAttribute(ATTR_ARCHETYPE_ID, "uuid", v).withAttribute(ATTR_CATALOG_ID, "string", v);
        return new ShopProduct(sku, key, kind, productType, p.attributes, prices, grants, tokens, oncePerAccount,
                contents, themeDeck, themeLegal, deckBox);
    }

    /** The client's Archetype JSON: [{"name": id, "value": v}] with json attributes as real JSON. */
    public Map<String, Object> toArchetypeJson() {
        List<Map<String, Object>> attrs = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : attributes.entrySet()) {
            JsonNode a = e.getValue();
            JsonNode v = a.path("value");
            if ("json".equals(a.path("type").asText()) && v.isTextual()) {
                try {
                    v = JSON.readTree(v.asText());
                } catch (Exception ignored) {
                    // keep the raw string
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", Long.parseLong(e.getKey()));
            m.put("value", v);
            attrs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("archetypeID", guid);
        out.put("attributes", attrs);
        return out;
    }

    public Map<String, Object> priceJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", 77381929L); // AttrID.SHOP_PRICE_MAP
        m.put("value", prices);
        return m;
    }

    public String lowerGuid() {
        return guid.toLowerCase(Locale.ROOT);
    }
}
