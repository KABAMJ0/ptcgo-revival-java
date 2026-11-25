package com.ptcgo.revival.game.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.game.wire.Attr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A physical card on the board: Pokemon, Energy or Trainer. In-play state lives in attributes, as on the client:
 * damage = max HP (attributeOriginals[HP]) minus remaining HP (attribute HP), special conditions = attribute 200340,
 * attachments (Energy, Tools, tucked evolutions) = children.
 */
public final class CardEntity extends Entity {
    public enum Kind {
        POKEMON("com.direwolfdigital.cake.rules.entities.Pokemon"),
        ENERGY("com.direwolfdigital.cake.rules.entities.Energy"),
        TRAINER("com.direwolfdigital.cake.rules.entities.TrainerCard");

        public final String clientClass;

        Kind(String clientClass) {
            this.clientClass = clientClass;
        }
    }

    /** Zones that hide a card even from its owner (own deck order / prize faces). */
    private static final Set<String> HIDDEN_FROM_OWNER_AREAS = Set.of("deck", "prizePile");
    /** Zones whose contents both players may see. */
    private static final Set<String> PUBLIC_AREAS =
            Set.of("activePokemonArea", "bench", "discard", "lostZone", "activeStadium", "activeTrainer");

    public final Catalog.Card card;
    public final Kind kind;
    /** Turn number this card was attached on (energy), informational. */
    public int attachedOnTurn;

    public CardEntity(String id, Catalog.Card card, String owningPlayerId) {
        super(id, owningPlayerId, card.guid());
        this.card = card;
        this.kind = kindOf(card);
        // Full render attribute set so the client's card-face renderer can build its image request.
        JsonNode wire = card.wire();
        if (wire != null) {
            wire.fields().forEachRemaining(e -> {
                int key = Integer.parseInt(e.getKey());
                if (key == Attr.EXPANSION) return; // _match_render_attributes pops it
                Object v = decode(e.getValue());
                if (v != null) set(key, v);
            });
        }
        // The mulligan-reveal carousel resolves archetypes through attribute 10000.
        set(Attr.ARCHETYPE_ID, card.guid());
        if (kind == Kind.POKEMON) {
            int hp = card.intAttribute(Attr.HP, 100);
            set(Attr.HP, hp);
            attributeOriginals.put(Attr.HP, hp);
        }
    }

    private static Object decode(JsonNode attr) {
        String type = attr.path("type").asText("");
        JsonNode v = attr.get("value");
        if (v == null || v.isNull()) return null;
        if (type.equals("json") && v.isTextual()) {
            try {
                return com.ptcgo.revival.net.WargCodec.JSON.readTree(v.asText());
            } catch (java.io.IOException e) {
                return v.asText();
            }
        }
        if (v.isInt() || v.isLong()) return v.asLong() > Integer.MAX_VALUE ? (Object) v.asLong() : (Object) v.asInt();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isTextual()) return v.asText();
        return v;
    }

    public static Kind kindOf(Catalog.Card c) {
        int t = c.intAttribute(Attr.CARD_TYPE, -1);
        if (t == Attr.CT_POKEMON) return Kind.POKEMON;
        if (t == Attr.CT_ENERGY) return Kind.ENERGY;
        if (t == Attr.CT_TRAINER) return Kind.TRAINER;
        return switch (c.kind()) {
            case "pokemon" -> Kind.POKEMON;
            case "energy" -> Kind.ENERGY;
            default -> Kind.TRAINER;
        };
    }

    @Override
    public String entityName() {
        return kind.clientClass;
    }

    public boolean isPokemon() {
        return kind == Kind.POKEMON;
    }

    public boolean isEnergy() {
        return kind == Kind.ENERGY;
    }

    public boolean isTrainer() {
        return kind == Kind.TRAINER;
    }

    /** Name of the PlayArea this card ultimately sits in, walking up through the Pokemon it is attached to. */
    public String containingAreaName() {
        for (Entity n = parent; n != null; n = n.parent) {
            if (n instanceof PlayArea a) return a.areaName();
        }
        return null;
    }

    @Override
    public boolean isHiddenFrom(String viewerId) {
        if (viewerId == null) return false;
        String area = containingAreaName();
        if (faceUp && "prizePile".equals(area)) return false;
        if (viewerId.equals(owningPlayerId)) return area != null && HIDDEN_FROM_OWNER_AREAS.contains(area);
        return area == null || !PUBLIC_AREAS.contains(area);
    }

    // ---- in-play helpers -----------------------------------------------------------------------

    public int hp() {
        return getInt(Attr.HP, 0);
    }

    public int maxHp() {
        Object o = attributeOriginals.get(Attr.HP);
        return o instanceof Number n ? n.intValue() : hp();
    }

    /** Damage taken, in HP (counters x 10). */
    public int damage() {
        return Math.max(0, maxHp() - hp());
    }

    public List<String> conditions() {
        Object v = get(Attr.SPECIAL_CONDITIONS);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        else if (v instanceof JsonNode j && j.isArray()) j.forEach(n -> out.add(n.asText()));
        return out;
    }

    public void setConditions(List<String> c) {
        set(Attr.SPECIAL_CONDITIONS, new ArrayList<>(c));
    }

    public int stage() {
        return getInt(Attr.STAGE, Attr.STAGE_BASIC);
    }

    public int trainerType() {
        return getInt(Attr.TRAINER_TYPE, -1);
    }

    public boolean hasSubtype(String subtype) {
        return card.hasSubtypeIgnoreCase(subtype);
    }

    public List<Integer> intList(int key) {
        Object v = get(key);
        List<Integer> out = new ArrayList<>();
        if (v instanceof JsonNode j) {
            if (j.isArray()) j.forEach(n -> out.add(n.asInt()));
            else if (j.isNumber()) out.add(j.asInt());
        } else if (v instanceof List<?> l) {
            for (Object o : l) out.add(((Number) o).intValue());
        } else if (v instanceof Number n) {
            out.add(n.intValue());
        }
        return out;
    }

    /** Parsed PIE_ABILITIES rows (attacks and abilities as the client displays them). */
    public List<JsonNode> pieAbilities() {
        List<JsonNode> out = new ArrayList<>();
        if (get(Attr.PIE_ABILITIES) instanceof JsonNode j && j.isArray()) j.forEach(out::add);
        return out;
    }

    /** Energy provided options ({"options": [[types...]]}), e.g. [[6]] or [[1, 1]]. */
    public List<List<Integer>> energyOptions() {
        List<List<Integer>> out = new ArrayList<>();
        if (get(Attr.ENERGY_INFO) instanceof JsonNode j) {
            for (JsonNode opt : j.path("options")) {
                List<Integer> o = new ArrayList<>();
                opt.forEach(n -> o.add(n.asInt()));
                out.add(o);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return card.displayName() + "#" + entityId.substring(0, 8);
    }

    /** Debug helper. */
    public Map<String, Object> summary() {
        return Map.of("id", entityId, "name", card.displayName());
    }
}
