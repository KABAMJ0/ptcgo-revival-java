package com.ptcgo.revival.game.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.catalog.Catalog;

import java.util.ArrayList;
import java.util.List;

/** The avatar customization item guids sent in gameOptions (avatar.py get_default_avatar_items_list). */
public final class AvatarItems {
    private AvatarItems() {}

    /** 16 item guids, one per customization group: the first Male item of each group, else a dummy id. */
    public static List<String> defaults(Catalog catalog) {
        JsonNode archetypes = catalog.payloads().get("avatar_archetypes").map(n -> n.path("archetypes")).orElse(null);
        List<String> out = new ArrayList<>();
        for (int g = 0; g < 16; g++) {
            if (g == 15) {
                // Our custom light skin color GUID as the default skin tone.
                out.add("00000000-0000-0000-0000-000000000a0f");
                continue;
            }
            String match = null;
            if (archetypes != null) {
                for (JsonNode a : archetypes) {
                    JsonNode attrs = a.path("attributes");
                    if (attrs.path("200890").path("value").asInt(-1) == g && attrs.path("10220").path("value").asInt(-1) == 1) {
                        match = a.path("guid").asText();
                        break;
                    }
                }
            }
            out.add(match != null ? match : String.format("00000000-0000-0000-0000-000000000a%02x", g));
        }
        return out;
    }

    /** Items of the account's active avatar deck (attribute 201310 == ["True"], or the only one), else null. */
    public static List<String> fromAvatarDecks(List<JsonNode> avatarDeckData) {
        for (JsonNode deck : avatarDeckData) {
            boolean active = false;
            for (JsonNode attr : deck.path("attributes")) {
                if (attr.path("name").asInt() == 201310 && attr.path("value").isArray() && attr.path("value").size() == 1
                        && "True".equals(attr.path("value").get(0).asText())) active = true;
            }
            if (!active && avatarDeckData.size() != 1) continue;
            JsonNode items = deck.path("piles").path("AvatarItems");
            if (!items.isArray() || items.isEmpty()) items = deck.path("cards").path("AvatarItems");
            if (items.isArray() && !items.isEmpty()) {
                List<String> out = new ArrayList<>();
                items.forEach(i -> out.add(i.asText()));
                return out;
            }
        }
        return null;
    }
}
