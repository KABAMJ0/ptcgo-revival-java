package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ptcgo.revival.catalog.Catalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds and holds the shop listing (ptcgo/shop + ptcgo/game/models/shop.py): automatic booster pricing for every
 * eligible set, admin/seeded {@code shop_items} rows (which override and extend it), SKU aliases and the featured and
 * top-selling lists. {@link #current()} is an immutable snapshot; call {@link #reload()} after shop rows change.
 */
public final class ShopManager {
    private static final Logger LOG = LoggerFactory.getLogger(ShopManager.class);
    public static final long DEFAULT_BOOSTER_PRICE = 200;
    public static final String TOKENS_GUID = "00000000-0000-0000-0000-000000000002";
    private static final List<String> LOCALES = List.of("en", "de", "fr", "it", "es", "ptb", "en_US", "default");
    /** Types listed under SKU GUIDs: the client evicts real shop GUIDs from the collection on shop exit. */
    private static final Set<Integer> SKU_LISTED_TYPES = Set.of(ShopProduct.TYPE_PACKS, ShopProduct.TYPE_DECKS,
            ShopProduct.TYPE_DECK_BOX, ShopProduct.TYPE_COINS, ShopProduct.TYPE_SLEEVE);
    /** Sets with more than this many cards are sold as boosters (MIN_CARD_SCRIPTS_FOR_BOOSTER). */
    public static final int MIN_CARDS_FOR_BOOSTER = 10;

    /** An immutable view of the shop. */
    public static final class Shop {
        final Map<String, ShopProduct> available = new LinkedHashMap<>();
        final Map<String, String> skuToPack = new HashMap<>();
        final List<String> featured = new ArrayList<>(); // listed guids, in order
        final List<String> topSelling = new ArrayList<>();
        final String placeholderImage;

        Shop(String placeholderImage) {
            this.placeholderImage = placeholderImage;
        }

        /** Exact-key lookup, as Python's {@code guid in shop.available_products}. */
        public ShopProduct listing(String guid) {
            return guid == null ? null : available.get(guid);
        }

        public ShopProduct listingIgnoreCase(String guid) {
            ShopProduct p = listing(guid);
            return p != null || guid == null ? p : available.get(guid.toLowerCase(Locale.ROOT));
        }

        public Map<String, ShopProduct> available() {
            return java.util.Collections.unmodifiableMap(available);
        }

        /** Maps a shop SKU GUID back to the real product GUID (identity if not a SKU). */
        public String resolve(String guid) {
            return skuToPack.getOrDefault(guid, guid);
        }

        public List<String> availableGuids() {
            return new ArrayList<>(available.keySet());
        }

        public Map<String, Object> priceMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            available.forEach((g, p) -> m.put(g, p.priceJson()));
            return m;
        }

        public List<Map<String, Object>> newArchetypes() {
            return available.values().stream().map(ShopProduct::toArchetypeJson).collect(Collectors.toList());
        }

        public List<Map<String, Object>> featuredJson() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String guid : featured) {
                ShopProduct p = available.get(guid);
                if (p == null) continue;
                JsonNode img = p.attribute(ShopProduct.ATTR_IMAGE_URL);
                String url = img == null ? placeholderImage : img.asText();
                Map<String, Object> urls = new LinkedHashMap<>();
                for (String locale : LOCALES) urls.put(locale, List.of(url, ""));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productID", guid);
                m.put("urls", urls);
                out.add(m);
            }
            return out;
        }

        public List<Map<String, Object>> topSellingJson() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String guid : topSelling) if (available.containsKey(guid)) out.add(available.get(guid).toArchetypeJson());
            return out;
        }

        /** {guid: {"bannerText": name, "bannerImage": placeholder}} for GetAvailableProducts. */
        public Map<String, Object> archetypeProperties() {
            Map<String, Object> props = new LinkedHashMap<>();
            for (Map.Entry<String, ShopProduct> e : available.entrySet()) {
                JsonNode name = e.getValue().attribute(ShopProduct.ATTR_NAME);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("bannerText", name != null ? name : JsonNodeFactory.instance.objectNode().put("id", ""));
                m.put("bannerImage", placeholderImage);
                props.put(e.getKey(), m);
            }
            return props;
        }
    }

    private final ShopProducts products;
    private final Catalog catalog;
    private final ShopRepository repo;
    private final PackMerges merges;
    private final String placeholderImage;
    private final JsonNode setMeta;
    private volatile Shop current;

    public ShopManager(ShopProducts products, Catalog catalog, ShopRepository repo, PackMerges merges, String placeholderImage) {
        this(products, catalog, repo, merges, placeholderImage, Resources.read("set_meta.json"));
    }

    ShopManager(ShopProducts products, Catalog catalog, ShopRepository repo, PackMerges merges, String placeholderImage,
                JsonNode setMeta) {
        this.products = products;
        this.catalog = catalog;
        this.repo = repo;
        this.merges = merges;
        this.placeholderImage = placeholderImage;
        this.setMeta = setMeta;
        reload();
    }

    public Shop current() {
        return current;
    }

    /** Set codes (upper case) with enough cards to be sold as boosters. */
    Set<String> eligibleSets() {
        return eligibleSets(catalog);
    }

    static Set<String> eligibleSets(Catalog catalog) {
        Set<String> out = new TreeSet<>();
        for (String code : catalog.setCodes()) {
            if (catalog.guidsInSet(code).size() > MIN_CARDS_FOR_BOOSTER) out.add(code.toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private ShopProduct tokensProduct() {
        Map<String, JsonNode> a = new LinkedHashMap<>();
        var f = JsonNodeFactory.instance;
        a.put("10140", typed("json", f.textNode("{\"id\": \"Trainer Tokens\"}")));
        a.put("10020", typed("string", f.textNode("core")));
        a.put("10540", typed("int", f.numberNode(6)));
        a.put("10200", typed("int", f.numberNode(-706482148)));
        a.put("10000", typed("uuid", f.textNode(TOKENS_GUID)));
        a.put("10510", typed("string", f.textNode(placeholderImage)));
        a.put("10570", typed("string", f.textNode(TOKENS_GUID)));
        a.put("200580", typed("string", f.textNode("core")));
        a.put("10190", typed("int", f.numberNode(100)));
        return new ShopProduct(TOKENS_GUID, "core", ShopProduct.Kind.OTHER, 6, a,
                List.of(new ShopProduct.Price(Currency.TRAINER_TOKENS, 0)), null, 0, false, null, false, false, null);
    }

    private static JsonNode typed(String type, JsonNode value) {
        var o = JsonNodeFactory.instance.objectNode();
        o.put("type", type);
        o.set("value", value);
        return o;
    }

    public synchronized void reload() {
        Shop shop = new Shop(placeholderImage);
        try {
            ShopProduct tokens = tokensProduct();
            shop.available.put(tokens.guid(), tokens);

            List<ShopRepository.ShopItem> items;
            try {
                items = repo.listShopItems(false);
            } catch (SQLException e) {
                LOG.warn("[Shop] Could not load shop items from DB ({}); using automatic listing only.", e.getMessage());
                items = List.of();
            }
            Map<String, ShopRepository.ShopItem> itemsByGuid = new HashMap<>();
            for (var i : items) itemsByGuid.put(i.productGuid().toLowerCase(Locale.ROOT), i);

            // 1. Automatic booster offerings: every eligible set.
            Set<String> eligible = eligibleSets();
            for (ShopProduct p : products.all()) {
                if (p.productType() != ShopProduct.TYPE_PACKS) continue;
                if (!eligible.contains(p.key().toUpperCase(Locale.ROOT))) continue;
                if (p.isBundle()) continue; // a bundle sells only through its shop row
                if (merges.isHiddenPack(p.key())) continue; // promos, galleries, vaults ... are not sold as packs
                if (itemsByGuid.containsKey(p.lowerGuid())) continue; // an admin row takes precedence
                ShopProduct sku = addAsSku(shop, p.withPrices(List.of(new ShopProduct.Price(Currency.TRAINER_TOKENS, DEFAULT_BOOSTER_PRICE))));
                shop.topSelling.add(sku.guid());
            }

            // 2. Admin-configured items (enabled only; disabled rows suppress the automatic listing).
            for (var item : items) {
                if (!item.enabled()) continue;
                ShopProduct p = products.byGuid(item.productGuid());
                if (p == null) {
                    LOG.warn("[Shop] Shop item references unknown product {}", item.productGuid());
                    continue;
                }
                if (p.productType() == ShopProduct.TYPE_PACKS && !p.isBundle() && merges.isHiddenPack(p.key())) continue;
                ShopProduct priced = p.withPrices(List.of(new ShopProduct.Price(item.currency(), item.price())));
                ShopProduct listed;
                if (SKU_LISTED_TYPES.contains(p.productType())) {
                    listed = addAsSku(shop, priced);
                } else {
                    listed = priced;
                    shop.available.put(priced.guid(), priced);
                }
                if (item.featured()) shop.featured.add(listed.guid());
                if (item.topSelling() && !shop.topSelling.contains(listed.guid())) shop.topSelling.add(0, listed.guid());
            }

            numberPacksByRelease(shop);

            // Always feature something so the shop landing is not empty.
            if (shop.featured.isEmpty() && !shop.topSelling.isEmpty() && shop.available.containsKey(shop.topSelling.get(0))) {
                shop.featured.add(shop.topSelling.get(0));
            }
            LOG.info("[Shop] Loaded {} products ({} featured).", shop.available.size(), shop.featured.size());
        } catch (RuntimeException e) {
            LOG.error("[Shop] Failed to reload shop", e);
        }
        current = shop;
    }

    private static ShopProduct addAsSku(Shop shop, ShopProduct product) {
        ShopProduct sku = product.asSku();
        shop.available.put(sku.guid(), sku);
        shop.skuToPack.put(sku.guid(), product.guid());
        return sku;
    }

    /** Gives every listed pack a SET_NUMBER equal to its release rank (1 = oldest), from set_meta.json. */
    private void numberPacksByRelease(Shop shop) {
        record Meta(String code, String date, int order) {}
        List<Meta> metas = new ArrayList<>();
        setMeta.fields().forEachRemaining(e -> metas.add(new Meta(e.getKey(), e.getValue().path("releaseDate").asText(""),
                e.getValue().path("order").asInt(999))));
        metas.sort(java.util.Comparator.<Meta, String>comparing(m -> m.date().isEmpty() ? "9999" : m.date())
                .thenComparingInt(Meta::order).thenComparing(Meta::code));
        Map<String, Integer> rank = new HashMap<>();
        int position = 1;
        for (Meta m : metas) rank.put(m.code().toUpperCase(Locale.ROOT), position++);
        int undated = rank.size() + 1;
        for (Map.Entry<String, ShopProduct> e : new ArrayList<>(shop.available.entrySet())) {
            ShopProduct p = e.getValue();
            if (p.productType() != ShopProduct.TYPE_PACKS) continue;
            int number = rank.getOrDefault(p.key().toUpperCase(Locale.ROOT), undated);
            e.setValue(p.withAttribute(ShopProduct.ATTR_SET_NUMBER, "int", JsonNodeFactory.instance.numberNode(number)));
        }
    }
}
