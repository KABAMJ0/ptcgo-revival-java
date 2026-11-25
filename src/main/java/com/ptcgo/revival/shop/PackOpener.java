package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.catalog.Catalog.Card;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * Opens products into archetype GUIDs. Booster logic is a faithful port of {@code BoosterPack.open} in
 * ptcgo/game/models/product.py: a 4 common / 4 uncommon / 1 reverse-holo / 1 hit pack (one common replaced by a basic
 * energy where the set prints one), cards drawn by rarity weight, per-set {@link PackRules}, and {@link PackMerges}
 * inserts. The RNG is injectable so tests are deterministic.
 */
public final class PackOpener {
    /** The Coin Chest is matched by GUID, never by set key. */
    public static final String COIN_BOOSTER_GUID = Uuid5.of(Uuid5.NAMESPACE_URL, "ptcgo:product:COINBOOSTER").toString();
    public static final int COIN_BOOSTER_SIZE = 2;
    public static final double GOLD_ENERGY_CHANCE = 0.03;
    public static final String DEFAULT_COIN = "B9A4EA96-949E-11E1-890F-EFB676C7909C";
    /** Sets that printed no plain basic energies of their own: era energy set to use. */
    private static final Set<String> ENERGY_SOURCE_SETS = Set.of("SV1", "SV2", "SV3", "SV3PT5", "SV4", "SV4PT5", "SV5",
            "SV6", "SV6PT5", "SV7", "SV8", "SV8PT5", "SV9", "SV10", "ZSV10PT5");
    private static final String ENERGY_SOURCE = "sve";
    /** Used when a set has no cards loaded at all (BW1 starter pool in Python). */
    private static final List<String> DEFAULT_POOL = List.of("7f4966fd-4082-5e69-1855-fee0e79f0ffa",
            "7167880e-4d58-c350-8cc7-adab87e8c6f7", "68975361-b02b-330e-b4e3-98c3b8e4bcda",
            "383f19f1-cb84-085b-63cc-e1d14b062cf6", "3202abdb-b395-f0c9-9b15-d10187c68255",
            "b7b3e2a1-385f-1466-7dae-47c081ff4626", "8597f163-fb9d-980d-32bc-38edc02852ff",
            "338b9be4-7534-1268-5aa4-2663a605d657", "eb6ebb86-4606-d7cb-22c4-196d294d0794",
            "96e32e96-f9b0-5157-21ba-9cd01fd43ff3");

    private final Catalog catalog;
    private final ShopProducts products;
    private final PackRules rules;
    private final PackMerges merges;
    private final RandomGenerator rng;
    private final Map<String, List<Card>> cardsByKey = new HashMap<>();
    private final FoilRules foil = FoilRules.defaults();

    public PackOpener(Catalog catalog, ShopProducts products, PackRules rules, PackMerges merges, RandomGenerator rng) {
        this.catalog = catalog;
        this.products = products;
        this.rules = rules;
        this.merges = merges;
        this.rng = rng;
        for (Card c : catalog.cards()) cardsByKey.computeIfAbsent(c.key().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c);
    }

    /** Whether the card gets a holo foil by foil_rules.json (display hint for an opened card). */
    public boolean isFoil(Card card) {
        return foil.getsFoil(Rarity.name(rarity(card)), card.setCode(), card.subtypes());
    }

    /** What opening the product yields: pack cards, coins for the Coin Chest, a bundle's/deck's contents, else nothing. */
    public List<String> open(ShopProduct product) {
        if (product.guid().equalsIgnoreCase(COIN_BOOSTER_GUID)) return assembleCoinPack(COIN_BOOSTER_SIZE);
        return switch (product.kind()) {
            case PACK -> openBooster(product);
            case BUNDLE -> product.grants();
            case DECK -> product.contents();
            case OTHER -> List.of();
        };
    }

    // ---- coin chest ------------------------------------------------------------------------------

    static double coinWeight(ShopProduct coin) {
        String name = coin.name().toLowerCase(Locale.ROOT);
        if (name.contains("gold")) return 0.35;
        if (name.contains("silver")) return 0.6;
        return 1.0;
    }

    /** {@code size} different coin GUIDs weighted by {@link #coinWeight}, never the default coin. */
    public List<String> assembleCoinPack(int size) {
        List<ShopProduct> coins = new ArrayList<>();
        for (ShopProduct p : products.all()) {
            if (p.productType() == ShopProduct.TYPE_COINS && !p.guid().equalsIgnoreCase(DEFAULT_COIN)) coins.add(p);
        }
        if (coins.size() < size) throw new IllegalStateException("only " + coins.size() + " coins are loaded; a Coin Booster needs " + size);
        // Weighted sampling without replacement: each coin draws u**(1/w) and the largest keys win.
        Map<ShopProduct, Double> keys = new HashMap<>();
        for (ShopProduct c : coins) keys.put(c, Math.pow(rng.nextDouble(), 1.0 / coinWeight(c)));
        coins.sort(Comparator.comparing((ShopProduct c) -> keys.get(c)).reversed());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < size; i++) out.add(coins.get(i).guid());
        return out;
    }

    // ---- booster ---------------------------------------------------------------------------------

    private int rarity(Card c) {
        return c.intAttribute(Rarity.ATTR_RARITY, -1);
    }

    private int type(Card c) {
        return c.intAttribute(Rarity.ATTR_CARD_TYPE, -1);
    }

    private boolean isEnergy(Card c) {
        return type(c) == Rarity.CARD_TYPE_ENERGY;
    }

    private boolean specialEnergy(Card c) {
        return isEnergy(c) && c.boolAttribute(Rarity.ATTR_IS_SPECIAL_ENERGY);
    }

    private List<Card> cardsOfKey(String key) {
        return cardsByKey.getOrDefault(key.toLowerCase(Locale.ROOT), List.of());
    }

    private static List<Card> filter(List<Card> in, Predicate<Card> p) {
        List<Card> out = new ArrayList<>();
        for (Card c : in) if (p.test(c)) out.add(c);
        return out;
    }

    private <T> T choice(List<T> pool) {
        return pool.get(rng.nextInt(pool.size()));
    }

    List<String> openBooster(ShopProduct product) {
        List<Card> setCards = cardsOfKey(product.key());
        if (setCards.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < 10; i++) out.add(choice(DEFAULT_POOL));
            return out;
        }
        JsonNode keyAttr = product.attribute(ShopProduct.ATTR_SET_KEY);
        String setKey = keyAttr != null && !keyAttr.asText("").isEmpty() ? keyAttr.asText() : product.key();
        List<String> pack = assemble(setKey, setCards);
        // Trainer Galleries, Shiny Vaults and Classic Collections can replace the reverse-holo slot of the parent pack.
        return merges.applyInserts(setKey, pack, source -> {
            List<String> pool = new ArrayList<>();
            for (Card c : cardsOfKey(source)) if (!isEnergy(c)) pool.add(c.guid());
            return pool;
        }, rng);
    }

    /** Draws {@code count} cards by rarity weight (with replacement), scaled by the set's weight multipliers. */
    List<Card> pickRandom(List<Card> pool, int count, String setKey) {
        List<Card> out = new ArrayList<>();
        if (count <= 0 || pool.isEmpty()) return out;
        double[] cumulative = new double[pool.size()];
        double total = 0;
        for (int i = 0; i < pool.size(); i++) {
            Card c = pool.get(i);
            int rarity = rarity(c);
            double weight = 1.0;
            if (rarity >= Rarity.RARE_HOLO_EX && rarity <= Rarity.RARE_HOLO_VSTAR) weight = 0.45;
            else if (rarity == Rarity.RARE_ULTRA) weight = 0.2;
            else if (rarity == Rarity.RARE_SECRET || rarity == Rarity.RARE_RAINBOW) weight = 0.1;
            if (!setKey.isEmpty()) weight *= rules.multiplierFor(setKey, c);
            total += Math.max(0, weight);
            cumulative[i] = total;
        }
        for (int n = 0; n < count; n++) {
            if (total <= 0) {
                out.add(choice(pool)); // all weights zero: Python would raise; draw uniformly instead
                continue;
            }
            double r = rng.nextDouble() * total;
            int idx = 0;
            while (idx < cumulative.length - 1 && cumulative[idx] <= r) idx++;
            out.add(pool.get(idx));
        }
        return out;
    }

    private static List<String> guids(List<Card> cards) {
        List<String> out = new ArrayList<>();
        for (Card c : cards) out.add(c.guid());
        return out;
    }

    List<String> assemble(String setKey, List<Card> setCards) {
        List<Card> commons = filter(setCards, c -> !isEnergy(c) && rarity(c) == Rarity.COMMON);
        // Special energies are real set cards and drop at their printed rarity; plain basics have their own slot.
        List<Card> uncommons = filter(setCards, c -> (!isEnergy(c) || specialEnergy(c)) && rarity(c) == Rarity.UNCOMMON);
        List<Card> rares = filter(setCards, c -> !isEnergy(c) && (rarity(c) == Rarity.RARE || rarity(c) == Rarity.RARE_HOLO));
        List<Card> hits = filter(setCards, c -> (!isEnergy(c) || specialEnergy(c)) && rarity(c) >= Rarity.RARE);
        List<Card> reverseHolos = filter(setCards, c -> !isEnergy(c) && rarity(c) <= Rarity.RARE);
        Set<Integer> reverseHitRares = Set.of(Rarity.RARE_RADIANT, Rarity.ACE, Rarity.AMAZING, Rarity.BREAK_RARE);
        List<Card> reverseHolosAndHits = filter(setCards,
                c -> !isEnergy(c) && (rarity(c) <= Rarity.RARE || reverseHitRares.contains(rarity(c))));

        // Fallbacks in case a specific pool is empty due to incomplete data.
        List<Card> commonsPool = commons.isEmpty() ? setCards : commons;
        List<Card> uncommonsPool = uncommons.isEmpty() ? setCards : uncommons;
        List<Card> raresPool = rares.isEmpty() ? setCards : rares;
        List<Card> hitsPool = hits.isEmpty() ? setCards : hits;
        List<Card> revHoloPool = !reverseHolos.isEmpty() ? reverseHolosAndHits : setCards;

        JsonNode setRules = rules.rulesFor(setKey);
        // Cards reserved for a guaranteed slot are kept out of the random pools.
        Set<String> reserved = rules.excludeFromRandom(setKey);
        if (!reserved.isEmpty()) {
            commonsPool = narrowed(commonsPool, reserved);
            uncommonsPool = narrowed(uncommonsPool, reserved);
            raresPool = narrowed(raresPool, reserved);
            hitsPool = narrowed(hitsPool, reserved);
            revHoloPool = narrowed(revHoloPool, reserved);
        }

        List<String> pack = new ArrayList<>();
        if (setKey.equals("CEL25") && setRules.path("four_card_pack").asBoolean(false)) {
            // Celebrations really did give 4 cards a pack.
            pack.addAll(guids(pickRandom(raresPool, 2, setKey)));
            pack.addAll(guids(pickRandom(revHoloPool, 1, setKey)));
            pack.addAll(guids(pickRandom(hitsPool, 1, setKey)));
        } else {
            JsonNode slots = setRules.path("slots");
            int commonsN = slots.path("common").asInt(4);
            int uncommonsN = slots.path("uncommon").asInt(4);
            int holoN = slots.path("reverse_holo").asInt(1);
            int hitsN = slots.path("hit").asInt(1);

            // The set's OWN plain basics (Common/Uncommon); Rare-and-up ones are gold/secret energies and stay rare.
            List<Card> energyCards = filter(setCards, c -> isEnergy(c) && !c.boolAttribute(Rarity.ATTR_IS_SPECIAL_ENERGY)
                    && rarity(c) < Rarity.RARE);
            if (energyCards.isEmpty() && ENERGY_SOURCE_SETS.contains(setKey.toUpperCase(Locale.ROOT))) {
                energyCards = filter(cardsOfKey(ENERGY_SOURCE), c -> isEnergy(c) && !c.boolAttribute(Rarity.ATTR_IS_SPECIAL_ENERGY)
                        && rarity(c) < Rarity.RARE);
            }
            List<Card> goldEnergy = filter(setCards, c -> isEnergy(c) && !c.boolAttribute(Rarity.ATTR_IS_SPECIAL_ENERGY)
                    && rarity(c) >= Rarity.RARE);
            boolean energySlot = !setRules.has("energy_slot") || setRules.get("energy_slot").asBoolean(true);
            int energyN = commonsN >= 4 && !energyCards.isEmpty() && energySlot ? 1 : 0;

            pack.addAll(guids(pickRandom(commonsPool, commonsN - energyN, setKey)));
            if (energyN > 0) pack.add(choice(energyCards).guid());
            pack.addAll(guids(pickRandom(uncommonsPool, uncommonsN, setKey)));
            if (holoN > 0) pack.addAll(guids(pickRandom(revHoloPool, holoN, setKey)));
            if (hitsN > 0) {
                List<String> picks = guids(pickRandom(hitsPool, hitsN, setKey));
                if (!goldEnergy.isEmpty() && rng.nextDouble() < GOLD_ENERGY_CHANCE) {
                    picks.set(picks.size() - 1, choice(goldEnergy).guid());
                }
                pack.addAll(picks);
            }
        }
        if (rules.hasRules(setKey)) pack = applySetRules(setKey, pack, setCards, commonsPool);
        return pack;
    }

    private List<Card> narrowed(List<Card> pool, Set<String> reservedNames) {
        List<Card> filtered = filter(pool, c -> !reservedNames.contains(PackRules.nameOf(c)));
        return filtered.size() >= 4 ? filtered : pool;
    }

    /** Drops repeats of a name that may appear once, keeps the pack at its size, and places guaranteed cards last. */
    private List<String> applySetRules(String setKey, List<String> pack, List<Card> setCards, List<Card> refillPool) {
        Map<String, Card> byGuid = new HashMap<>();
        for (Card c : setCards) byGuid.put(c.guid(), c);
        int size = pack.size();
        List<String> out = new ArrayList<>(rules.dedupe(pack, byGuid, setKey));
        Set<String> unique = rules.uniquePerPack(setKey);
        int guard = 0;
        while (out.size() < size && !refillPool.isEmpty() && guard++ < 10_000) {
            String candidate = pickRandom(refillPool, 1, setKey).get(0).guid();
            Card card = byGuid.get(candidate);
            if (card != null && unique.contains(PackRules.nameOf(card)) && out.contains(candidate)) continue;
            out.add(candidate);
        }
        for (List<Card> pool : rules.guaranteedPicks(setKey, setCards)) {
            if (!out.isEmpty()) out.remove(out.size() - 1);
            out.add(choice(pool).guid());
        }
        return out;
    }

    /** Test/diagnostic helper: the distinct set codes a pack's cards come from. */
    public Set<String> setsOf(List<String> guids) {
        Set<String> s = new HashSet<>();
        for (String g : guids) {
            Card c = catalog.card(g);
            if (c != null) s.add(c.key());
        }
        return s;
    }
}
