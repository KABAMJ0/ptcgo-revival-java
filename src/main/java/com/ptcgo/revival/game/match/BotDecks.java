package com.ptcgo.revival.game.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.deck.ThemeDecks;
import com.ptcgo.revival.game.wire.Attr;
import com.ptcgo.revival.net.WargCodec;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** A real 60-card deck for the bot (Python: the first starter decklist that resolves to 60 cards). */
public final class BotDecks {
    private BotDecks() {}

    /** The first approved theme deck of exactly 60 cards that holds a Basic Pokemon; falls back to a synthetic deck. */
    public static Supplier<JsonNode> from(Catalog catalog, ThemeDecks themes) {
        return new Supplier<>() {
            private JsonNode cached;

            @Override
            public synchronized JsonNode get() {
                if (cached == null) {
                    for (List<String> contents : themes == null ? List.<List<String>>of() : themes.decks()) {
                        if (contents.size() == 60 && hasBasic(catalog, contents)) {
                            cached = build("Bot Deck", contents);
                            break;
                        }
                    }
                    if (cached == null) cached = synthetic(catalog);
                }
                return cached;
            }
        };
    }

    private static boolean hasBasic(Catalog catalog, List<String> guids) {
        for (String g : guids) {
            Catalog.Card c = catalog.card(g);
            if (c != null && c.kind().equals("pokemon") && c.intAttribute(Attr.STAGE, 0) == Attr.STAGE_BASIC) return true;
        }
        return false;
    }

    /** 20 copies of a Basic Pokemon that can attack, 40 matching basic Energy. */
    static JsonNode synthetic(Catalog catalog) {
        String pokemon = null, energy = null;
        for (Catalog.Card c : catalog.cards()) {
            if (!c.kind().equals("pokemon") || c.intAttribute(Attr.STAGE, -1) != Attr.STAGE_BASIC) continue;
            boolean attacks = false;
            JsonNode abilities = c.attribute(Attr.PIE_ABILITIES);
            JsonNode rows = abilities != null && abilities.isTextual() ? parse(abilities.asText()) : abilities;
            if (rows != null) for (JsonNode r : rows) if (r.path("abilityType").asText().equals("Attack")) attacks = true;
            if (!attacks) continue;
            JsonNode t = c.attribute(Attr.POKEMON_TYPES);
            JsonNode types = t != null && t.isTextual() ? parse(t.asText()) : t;
            int type = types != null && types.isArray() && types.size() > 0 ? types.get(0).asInt() : 1;
            for (Catalog.Card e : catalog.cards()) {
                if (!e.kind().equals("energy") || !e.hasSubtypeIgnoreCase("Basic")) continue;
                JsonNode info = e.attribute(Attr.ENERGY_INFO);
                JsonNode inf = info != null && info.isTextual() ? parse(info.asText()) : info;
                if (inf != null && inf.path("options").path(0).path(0).asInt(-1) == type) {
                    energy = e.guid();
                    break;
                }
            }
            if (energy != null) {
                pokemon = c.guid();
                break;
            }
        }
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        if (pokemon != null) {
            for (int i = 0; i < 20; i++) list.add(pokemon);
            for (int i = 0; i < 40; i++) list.add(energy);
        }
        return build("Bot Deck", list);
    }

    private static JsonNode parse(String s) {
        try {
            return WargCodec.JSON.readTree(s);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    public static JsonNode build(String name, List<String> guids) {
        ObjectNode d = WargCodec.JSON.createObjectNode();
        d.put("deckID", UUID.randomUUID().toString());
        d.put("deckName", name);
        ArrayNode deck = d.putObject("piles").putArray("deck");
        guids.forEach(deck::add);
        ArrayNode attrs = d.putArray("attributes");
        attrs.addObject().put("name", Attr.SELECTED_COIN).put("value", "B9A4EA96-949E-11E1-890F-EFB676C7909C");
        attrs.addObject().put("name", Attr.SELECTED_SLEEVE).put("value", "e079c0d3-b934-4fbd-b021-545106c75693");
        attrs.addObject().put("name", Attr.SELECTED_DECK_BOX).put("value", "e129b0d3-b934-4fbd-b021-545106c75694");
        return d;
    }

    /** True when the deck resolves to at least one known card (an empty deck can never play). */
    public static boolean playable(Catalog catalog, JsonNode deck) {
        if (deck == null) return false;
        JsonNode actual = deck.has("deck") && deck.get("deck").isObject() ? deck.get("deck") : deck;
        JsonNode piles = actual.path("piles");
        JsonNode list = piles.has("deck") ? piles.get("deck") : piles.path("CakePile");
        if (list.isArray()) for (JsonNode g : list) if (catalog.card(g.asText()) != null) return true;
        for (JsonNode ci : actual.path("cards")) if (catalog.card(ci.path("guid").asText()) != null) return true;
        return false;
    }
}
