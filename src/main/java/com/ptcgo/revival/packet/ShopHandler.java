package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptcgo.revival.shop.Currency;
import com.ptcgo.revival.shop.PackOpener;
import com.ptcgo.revival.shop.ShopManager;
import com.ptcgo.revival.shop.ShopProduct;
import com.ptcgo.revival.shop.ShopProducts;
import com.ptcgo.revival.shop.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shop listing, purchases and product opening. Behaviour (including which failures reply and which stay silent) follows
 * ptcgo/packets/handlers/shop.py. Unlike Python, a charge and the grant it pays for share one transaction, so a failed
 * opening never costs the player.
 */
public final class ShopHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ShopHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // ProductPurchaseFailure texts; unknown loc keys render verbatim in the failure dialog.
    public static final String PURCHASE_ERROR_SIGN_IN = "Sign in to buy products.";
    public static final String PURCHASE_ERROR_UNAVAILABLE = "This product is no longer available. Please reopen the shop.";
    public static final String PURCHASE_ERROR_GENERIC = "Your purchase could not be completed. You have not been charged.";
    public static final String OPEN_ERROR = "Unable to open these products. Please try again.";

    private final ShopManager shop;
    private final ShopRepository repo;
    private final ShopProducts products;
    private final PackOpener opener;

    public ShopHandler(ShopManager shop, ShopRepository repo, ShopProducts products, PackOpener opener) {
        this.shop = shop;
        this.repo = repo;
        this.products = products;
        this.opener = opener;
    }

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("PurchaseAndOpenProducts", this::purchaseAndOpenProducts);
        m.put("OpenProductsByArchetypeID", this::openProductsByArchetypeId);
        m.put("PurchaseArchetypes", this::purchaseArchetypes);
        m.put("PurchaseCatalogProducts", this::purchaseCatalogProducts);
        m.put("GetAvailableProducts", this::getAvailableProducts);
        m.put("GetFeaturedProducts", this::getFeaturedProducts);
        m.put("GetTopSellingProducts", this::getTopSellingProducts);
        m.put("GetThemeDeckContents", this::getThemeDeckContents);
        return m;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String owner(ClientContext c) {
        return c.account() != null ? c.account().accountId() : "0";
    }

    /** The client's Item shape (shared with the trade lots protocol). */
    static Map<String, Object> buildItem(String ownerId, String archetypeId, boolean tradable) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemID", UUID.randomUUID().toString());
        item.put("ownerID", ownerId);
        item.put("archetypeID", archetypeId);
        item.put("lockID", null);
        item.put("created", System.currentTimeMillis() / 1000);
        item.put("isTradable", tradable);
        item.put("name", "");
        item.put("invoiceID", null);
        item.put("attributes", List.of());
        return item;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) for (JsonNode n : array) out.add(n.isTextual() ? n.asText() : n.toString());
        return out;
    }

    private static boolean truthy(JsonNode n) {
        if (n == null || n.isNull()) return false;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isNumber()) return n.asDouble() != 0;
        if (n.isTextual()) return !n.asText().isEmpty();
        return !n.isEmpty();
    }

    /** Pushes a Notification; the client also dispatches its payload as a normal message. */
    private static void sendNotification(ClientContext c, String name, Object value) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("value", value);
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("notificationID", UUID.randomUUID().toString());
        n.put("payload", JSON.writeValueAsString(payload));
        n.put("notificationType", 0);
        c.send("Notification", n, 0);
    }

    private static void pushWallet(ClientContext c) throws SQLException {
        if (c.account() == null) return;
        c.send("CurrentWallet", DataSyncHandler.walletPayload(c.services().players().wallet(c.account().accountId())), 0);
    }

    private static void pushCollection(ClientContext c) throws SQLException {
        if (c.account() == null) return;
        c.send("CollectionCountFound", Map.of("collectionCountList", DataSyncHandler.collectionPayload(c)), 0);
    }

    private static Map<String, Object> error(String id) {
        return Map.of("id", id);
    }

    /** First listed price of each listing summed per currency (listings with no price add nothing). */
    private static Map<Long, Long> chargesOf(List<ShopProduct> listings) {
        Map<Long, Long> charges = new LinkedHashMap<>();
        for (ShopProduct l : listings) {
            if (l.prices().isEmpty()) continue;
            ShopProduct.Price p = l.prices().get(0);
            charges.merge(p.name(), Math.max(0, p.value()), Long::sum);
        }
        return charges;
    }

    /**
     * The older purchase paths charge the summed first price of every listing in ONE currency: the last priced
     * listing's. Returns {} when nothing is owed.
     */
    private static Map<Long, Long> legacyCharge(List<Map.Entry<ShopProduct, Integer>> basket) {
        long total = 0;
        Long currency = null;
        for (Map.Entry<ShopProduct, Integer> e : basket) {
            ShopProduct p = e.getKey();
            if (p != null && !p.prices().isEmpty()) {
                total += p.prices().get(0).value() * e.getValue();
                currency = p.prices().get(0).name();
            }
        }
        return currency != null && total > 0 ? Map.of(currency, total) : Map.of();
    }

    // ---- opening ---------------------------------------------------------------------------------

    private enum OpenStatus { OK, FUNDS, FAILED }

    private OpenStatus processOpening(ClientContext c, int rid, List<String> productIds, Boolean tradableOverride,
                                      boolean purchased, Map<Long, Long> charges) throws Exception {
        ShopManager.Shop snapshot = shop.current();
        ShopRepository.OpenOutcome outcome;
        List<ShopProduct> requested = new ArrayList<>();
        try {
            for (String id : productIds) {
                ShopProduct p = products.byGuid(snapshot.resolve(id));
                if (p == null) throw new IllegalArgumentException("Unknown product requested");
                requested.add(p);
            }
            if (c.account() == null) throw new IllegalArgumentException("Sign in before opening products");
            outcome = repo.openProducts(c.account().accountId(), requested, purchased, tradableOverride, charges, opener::open);
        } catch (Exception e) {
            LOG.error("[Shop] Product opening failed for {}", owner(c), e);
            c.send("ProductsOpenedFailure", Map.of("error", error(OPEN_ERROR)), rid);
            return OpenStatus.FAILED;
        }
        if (outcome.error() != null) {
            LOG.warn("[Shop] Player {} cannot pay for the opening: {}", c.account().username(), outcome.error());
            return OpenStatus.FUNDS;
        }
        String ownerId = owner(c);
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> openedProducts = new ArrayList<>();
        for (ShopRepository.OpenedRow row : outcome.opened()) {
            openedProducts.add(buildItem(ownerId, row.productGuid(), row.tradable()));
            for (String card : row.contents()) items.add(buildItem(ownerId, card, row.tradable()));
        }
        Map<String, Object> opened = new LinkedHashMap<>();
        opened.put("accountID", ownerId);
        opened.put("items", items);
        opened.put("products", openedProducts);
        opened.put("additionalData", Map.of());

        boolean anyPack = requested.stream().anyMatch(ShopProduct::isPack);
        boolean anyDeck = requested.stream().anyMatch(ShopProduct::isDeck);
        boolean anyBundle = requested.stream().anyMatch(ShopProduct::isBundle);
        if (anyPack) {
            c.send("ProductsOpened", opened, rid);
        } else {
            // No pack-opening sequence: the client only reacts to ProductsOpened as a notification.
            if (anyDeck) DataSyncHandler.getDeckList(c, null, 0, 0);
            sendNotification(c, "ProductsOpened", opened);
        }
        pushCollection(c);
        if (anyBundle) pushWallet(c);
        return OpenStatus.OK;
    }

    private void purchaseAndOpenProducts(ClientContext c, JsonNode v, int rid, int flags) throws Exception {
        ShopManager.Shop snapshot = shop.current();
        List<String> ids = new ArrayList<>();
        for (String g : strings(v == null ? null : v.get("products"))) if (snapshot.listing(g) != null) ids.add(g);

        if (ids.stream().anyMatch(id -> findBundle(snapshot.resolve(id)) != null)) {
            List<ShopProduct> listings = new ArrayList<>();
            for (String id : ids) listings.add(snapshot.listing(id));
            buyBundlesInstead(c, rid, snapshot, listings, "ProductsOpenedFailure", items -> {
                try {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("accountID", owner(c));
                    value.put("items", items);
                    value.put("products", List.of());
                    value.put("additionalData", Map.of());
                    sendNotification(c, "ProductsOpened", value);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            return;
        }
        List<Map.Entry<ShopProduct, Integer>> basket = new ArrayList<>();
        for (String id : ids) basket.add(Map.entry(snapshot.listing(id), 1));
        Map<Long, Long> charges = c.account() == null ? Map.of() : legacyCharge(basket);
        if (c.account() != null) c.services().players().wallet(c.account().accountId());
        Boolean override = v != null && v.hasNonNull("isTradable") ? truthy(v.get("isTradable")) : null;
        if (processOpening(c, rid, ids, override, true, charges) == OpenStatus.FUNDS) return;
        pushWallet(c); // unsolicited sync uses request id 0
    }

    private void openProductsByArchetypeId(ClientContext c, JsonNode v, int rid, int flags) throws Exception {
        Boolean override = v != null && v.hasNonNull("isTradable") ? truthy(v.get("isTradable")) : null;
        processOpening(c, rid, strings(v == null ? null : v.get("products")), override, false, Map.of());
    }

    // ---- purchases -------------------------------------------------------------------------------

    private ShopProduct findBundle(String guid) {
        ShopProduct p = products.byGuid(guid);
        return p != null && p.isBundle() ? p : null;
    }

    /** Each bundle replaced by its contents; tokens to credit; one-per-account claims. */
    private record Expanded(List<String> granted, long tokens, List<String> claims) {}

    private Expanded expandBundles(List<String> guids) {
        List<String> granted = new ArrayList<>(), claims = new ArrayList<>();
        long tokens = 0;
        for (String guid : guids) {
            ShopProduct b = findBundle(guid);
            if (b == null) {
                granted.add(guid);
                continue;
            }
            granted.addAll(b.grants());
            tokens += b.tokens();
            if (b.oncePerAccount()) claims.add(b.lowerGuid());
        }
        return new Expanded(granted, tokens, claims);
    }

    private record Bought(String error, List<Map<String, Object>> items) {}

    /**
     * Charges, grants and records limits for the listings in ONE transaction. A bundle is fulfilled here: its
     * contents are granted and its tokens credited, so nothing has to be opened afterwards.
     */
    private Bought buyAtomically(ClientContext c, ShopManager.Shop snapshot, List<ShopProduct> listings) {
        String account = c.account().accountId();
        Map<Long, Long> charges = chargesOf(listings);
        List<String> resolved = new ArrayList<>();
        for (ShopProduct l : listings) resolved.add(snapshot.resolve(l.guid()));
        Expanded ex = expandBundles(resolved);
        Map<Long, Long> credit = ex.tokens() > 0 ? Map.of(Currency.TRAINER_TOKENS, ex.tokens()) : Map.of();
        Map<String, Integer> grants = new LinkedHashMap<>();
        for (String g : ex.granted()) grants.merge(g, 1, Integer::sum);
        String error;
        try {
            c.services().players().wallet(account); // creates the default wallet on first use, like Python's Player
            error = repo.purchase(account, charges, grants, credit, ex.claims());
        } catch (Exception e) {
            LOG.error("[Shop] Purchase failed for {}", c.account().username(), e);
            error = PURCHASE_ERROR_GENERIC;
        }
        if (error != null) {
            LOG.warn("[Shop] Purchase rejected for {}: {}", c.account().username(), error);
            return new Bought(error, List.of());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String g : ex.granted()) items.add(buildItem(account, g, true));
        return new Bought(null, items);
    }

    /**
     * A bundle bought through the open-on-purchase or legacy archetype paths goes through the same atomic purchase as
     * the catalog path, so its one-per-account limit and token credit cannot be skipped by choosing another path.
     */
    private void buyBundlesInstead(ClientContext c, int rid, ShopManager.Shop snapshot, List<ShopProduct> listings,
                                   String failureMessage, Consumer<List<Map<String, Object>>> success) throws SQLException {
        if (c.account() == null) {
            c.send(failureMessage, Map.of("error", error(PURCHASE_ERROR_SIGN_IN)), rid);
            return;
        }
        Bought bought = buyAtomically(c, snapshot, listings);
        if (bought.error() != null) c.send(failureMessage, Map.of("error", error(bought.error())), rid);
        else success.accept(bought.items());
        pushWallet(c);
        pushCollection(c);
    }

    private void purchaseArchetypes(ClientContext c, JsonNode v, int rid, int flags) throws Exception {
        ShopManager.Shop snapshot = shop.current();
        Map<String, Integer> archetypes = new LinkedHashMap<>();
        JsonNode raw = v == null ? null : v.get("archetypes");
        if (raw != null && raw.isObject()) {
            raw.fields().forEachRemaining(e -> {
                if (snapshot.listing(e.getKey()) != null && e.getValue().isIntegralNumber() && e.getValue().asInt() > 0) {
                    archetypes.put(e.getKey(), e.getValue().asInt());
                }
            });
        }
        if (archetypes.keySet().stream().anyMatch(a -> findBundle(snapshot.resolve(a)) != null)) {
            List<ShopProduct> listings = new ArrayList<>();
            archetypes.forEach((a, qty) -> {
                for (int i = 0; i < qty; i++) listings.add(snapshot.listing(a));
            });
            buyBundlesInstead(c, rid, snapshot, listings, "ArchetypesPurchaseFailure", items -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("items", items);
                m.put("transactionID", UUID.randomUUID().toString());
                c.send("ArchetypesPurchased", m, rid);
            });
            return;
        }
        List<Map.Entry<ShopProduct, Integer>> basket = new ArrayList<>();
        archetypes.forEach((a, qty) -> basket.add(Map.entry(snapshot.listing(a), qty)));
        List<Map<String, Object>> purchased = new ArrayList<>();
        Map<String, Integer> grants = new LinkedHashMap<>();
        archetypes.forEach((a, qty) -> {
            String packGuid = snapshot.resolve(a);
            for (int i = 0; i < qty; i++) purchased.add(buildItem(owner(c), packGuid, true));
            grants.merge(packGuid, qty, Integer::sum);
        });
        if (c.account() != null) {
            c.services().players().wallet(c.account().accountId());
            String err = repo.purchase(c.account().accountId(), legacyCharge(basket), grants, Map.of(), List.of());
            if (err != null) {
                LOG.warn("[Shop] Player {} cannot buy archetypes: {}", c.account().username(), err);
                return; // Python sends nothing when the player cannot pay
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", purchased);
        m.put("transactionID", UUID.randomUUID().toString());
        c.send("ArchetypesPurchased", m, rid);
        pushCollection(c);
        pushWallet(c);
    }

    private void purchaseFailed(ClientContext c, int rid, String reason) throws SQLException {
        c.send("ProductPurchaseFailure", Map.of("error", error(reason)), rid);
        pushWallet(c); // the client already deducted its display locally
    }

    /** Shop listings for a request (one per unit), or null if any is unknown. Prefers exact listing GUIDs in selections. */
    private static List<ShopProduct> resolveListings(ShopManager.Shop snapshot, JsonNode v) {
        List<String> selections = strings(v == null ? null : v.get("selections"));
        List<String> requested = selections;
        if (requested.isEmpty()) requested = strings(v == null ? null : v.get("products"));
        if (requested.isEmpty()) requested = strings(v == null ? null : v.get("productIDs"));
        List<ShopProduct> listings = new ArrayList<>();
        for (String id : requested) {
            ShopProduct l = snapshot.listingIgnoreCase(id);
            if (l == null && selections.isEmpty()) {
                for (ShopProduct p : snapshot.available().values()) {
                    if (p.name().equals(id)) {
                        l = p;
                        break;
                    }
                }
            }
            if (l == null) {
                LOG.warn("[Shop] Purchase references unavailable product {}", id);
                return null;
            }
            listings.add(l);
        }
        return listings.isEmpty() ? null : listings;
    }

    private void purchaseCatalogProducts(ClientContext c, JsonNode v, int rid, int flags) throws Exception {
        if (c.account() == null) {
            purchaseFailed(c, rid, PURCHASE_ERROR_SIGN_IN);
            return;
        }
        ShopManager.Shop snapshot = shop.current();
        List<ShopProduct> listings = resolveListings(snapshot, v);
        if (listings == null) {
            purchaseFailed(c, rid, PURCHASE_ERROR_UNAVAILABLE);
            return;
        }
        Map<Long, Long> charges = chargesOf(listings);
        JsonNode type = v == null ? null : v.get("purchaseType");
        if (type != null && !type.isNull() && charges.values().stream().anyMatch(x -> x != 0) && !charges.containsKey(type.asLong())) {
            LOG.warn("[Shop] purchaseType {} differs from listed currencies {}", type, charges.keySet());
        }
        Bought bought = buyAtomically(c, snapshot, listings);
        if (bought.error() != null) {
            purchaseFailed(c, rid, bought.error());
            return;
        }
        Map<String, Object> purchased = new LinkedHashMap<>();
        purchased.put("invoiceID", UUID.randomUUID().toString());
        purchased.put("items", bought.items());
        c.send("ProductsPurchased", purchased, rid);
        // No collection push: the dialog command adds these items client-side (it would double).
        sendNotification(c, "ProductsFulfilled", Map.of("items", bought.items()));
        pushWallet(c);
    }

    // ---- listings --------------------------------------------------------------------------------

    private void getAvailableProducts(ClientContext c, JsonNode v, int rid, int flags) {
        ShopManager.Shop snapshot = shop.current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("availableProducts", snapshot.availableGuids());
        m.put("priceMap", snapshot.priceMap());
        m.put("newArchetypes", snapshot.newArchetypes());
        m.put("archetypeProperties", snapshot.archetypeProperties());
        c.send("AvailableProducts", m, rid);
    }

    private void getFeaturedProducts(ClientContext c, JsonNode v, int rid, int flags) {
        c.send("FeaturedProducts", Map.of("products", shop.current().featuredJson()), rid);
    }

    private void getTopSellingProducts(ClientContext c, JsonNode v, int rid, int flags) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoiceID", UUID.randomUUID().toString());
        m.put("products", shop.current().topSellingJson());
        c.send("TopSellingProducts", m, rid);
    }

    private void getThemeDeckContents(ClientContext c, JsonNode v, int rid, int flags) {
        c.send("ThemeDeckContentsMap", Map.of("themeDeckContentsMap", products.deckContentsMap()), rid);
    }
}
