package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Every product, loaded from the exporter's {@code products.json} (guid, key, kind, attributes, bundle, deck). */
public final class ShopProducts {
    private static final Logger LOG = LoggerFactory.getLogger(ShopProducts.class);
    private final List<ShopProduct> list = new ArrayList<>();
    private final Map<String, ShopProduct> byGuid = new LinkedHashMap<>();

    public ShopProducts(Collection<ShopProduct> products) {
        for (ShopProduct p : products) {
            list.add(p);
            byGuid.put(p.lowerGuid(), p);
        }
    }

    public static ShopProducts empty() {
        return new ShopProducts(List.of());
    }

    /** Reads {@code <dir>/products.json}; a missing file means no products. */
    public static ShopProducts load(Path dir) throws IOException {
        Path file = dir.resolve("products.json");
        if (!Files.isRegularFile(file)) {
            LOG.warn("[Shop] {} not found; no products", file);
            return empty();
        }
        List<ShopProduct> out = new ArrayList<>();
        for (JsonNode n : new ObjectMapper().readTree(file.toFile())) out.add(parse(n));
        LOG.info("[Shop] Loaded {} products", out.size());
        return new ShopProducts(out);
    }

    public static ShopProduct parse(JsonNode n) {
        String kind = n.path("kind").asText("");
        ShopProduct.Kind k = switch (kind) {
            case "booster_pack" -> ShopProduct.Kind.PACK;
            case "bundle" -> ShopProduct.Kind.BUNDLE;
            case "deck" -> ShopProduct.Kind.DECK;
            default -> ShopProduct.Kind.OTHER;
        };
        Map<String, JsonNode> attrs = new LinkedHashMap<>();
        n.path("attributes").fields().forEachRemaining(e -> attrs.put(e.getKey(), e.getValue()));
        List<ShopProduct.Price> prices = new ArrayList<>();
        for (JsonNode p : n.path("prices")) prices.add(new ShopProduct.Price(p.path("name").asLong(), p.path("value").asLong()));
        JsonNode bundle = n.path("bundle");
        JsonNode deck = n.path("deck");
        List<String> grants = new ArrayList<>();
        bundle.path("grants").forEach(g -> grants.add(g.asText().toLowerCase(Locale.ROOT)));
        List<String> contents = new ArrayList<>();
        deck.path("contents").forEach(g -> contents.add(g.asText()));
        return new ShopProduct(n.path("guid").asText(""), n.path("key").asText(""), k, n.path("product_type").asInt(-1),
                attrs, prices, grants, bundle.path("tokens").asInt(0), bundle.path("once_per_account").asBoolean(false),
                contents, deck.path("is_theme_deck").asBoolean(false), deck.path("theme_legal").asBoolean(false),
                deck.hasNonNull("deck_box") ? deck.get("deck_box").asText() : null);
    }

    public List<ShopProduct> all() {
        return java.util.Collections.unmodifiableList(list);
    }

    /** Case-insensitive lookup by guid. */
    public ShopProduct byGuid(String guid) {
        return guid == null ? null : byGuid.get(guid.toLowerCase(Locale.ROOT));
    }

    /** Decks that carry contents, {product guid: card guids} (the ThemeDeckContentsMap payload). */
    public Map<String, List<String>> deckContentsMap() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (ShopProduct p : list) if (p.isDeck() && !p.contents().isEmpty()) m.put(p.guid(), p.contents());
        return m;
    }
}
