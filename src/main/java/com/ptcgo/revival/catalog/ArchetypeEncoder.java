package com.ptcgo.revival.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encodes catalogue entries as dwd.Protobuf.Archetype / Collection.ArchetypesFound. Card bytes per key are
 * built once and reused: repeated protobuf fields concatenate, so they can be spliced into any response.
 */
public final class ArchetypeEncoder {
    // dwd.Protobuf.Object.Type
    static final int T_STRING = 3, T_BOOL = 4, T_INT = 5, T_FLOAT = 6, T_UUID = 7, T_JSON = 8;
    static final int ATTR_SET_CACHE_KEY = 200580;
    static final int ATTR_EXPANSION = 10020;
    public static final String MESSAGE_NAME = "dwd.Protobuf.Collection.ArchetypesFound";

    private final Catalog catalog;
    private final Map<String, byte[]> cardBytesByKey = new ConcurrentHashMap<>();

    public ArchetypeEncoder(Catalog catalog) {
        this.catalog = catalog;
    }

    /** The repeated {@code archetypes} field for every card of {@code key} (empty if there are none). */
    public byte[] cardArchetypes(String key) {
        return cardBytesByKey.computeIfAbsent(key, k -> {
            ProtoWriter w = new ProtoWriter();
            for (String guid : catalog.guidsInSet(k)) w.raw(archetype(guid, catalog.card(guid).wire(), null));
            return w.toByteArray();
        });
    }

    /** One {@code archetypes} entry for a product, with the cache key and expansion forced to the requested key. */
    public byte[] productArchetype(Catalog.Product p, String requestedKey) {
        return archetype(p.guid(), p.attributes(), requestedKey);
    }

    public static final String AVATAR_MESSAGE_NAME = "dwd.Protobuf.cake.item.AllAvatarArchetypesFound";

    /** AllAvatarArchetypesFound: archetypes (1), checksum (2). {@code list} is [{guid, attributes}]. */
    public static byte[] avatars(JsonNode list, String checksum) {
        ProtoWriter w = new ProtoWriter();
        for (JsonNode a : list) w.raw(archetype(a.path("guid").asText(), a.path("attributes"), null));
        return w.string(2, checksum).toByteArray();
    }

    /** A serialized ArchetypesFound: archetypes (1), checksum (2), key (3). */
    public static byte[] found(byte[] archetypeEntries, List<byte[]> more, String checksum, String key) {
        ProtoWriter w = new ProtoWriter().raw(archetypeEntries);
        for (byte[] b : more) w.raw(b);
        return w.string(2, checksum).string(3, key).toByteArray();
    }

    static byte[] archetype(String guid, JsonNode attrs, String overrideKey) {
        ProtoWriter a = new ProtoWriter().bytes(1, uuid(guid));
        boolean seenCache = false, seenExpansion = false;
        for (Iterator<Map.Entry<String, JsonNode>> it = attrs.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            int id = Integer.parseInt(e.getKey());
            JsonNode value = e.getValue();
            if (overrideKey != null && (id == ATTR_SET_CACHE_KEY || id == ATTR_EXPANSION)) {
                value = stringValue(overrideKey);
                if (id == ATTR_SET_CACHE_KEY) seenCache = true; else seenExpansion = true;
            }
            a.bytes(2, attribute(id, value));
        }
        if (overrideKey != null) {
            if (!seenCache) a.bytes(2, attribute(ATTR_SET_CACHE_KEY, stringValue(overrideKey)));
            if (!seenExpansion) a.bytes(2, attribute(ATTR_EXPANSION, stringValue(overrideKey)));
        }
        return new ProtoWriter().bytes(1, a.toByteArray()).toByteArray(); // one entry of ArchetypesFound.archetypes
    }

    private static JsonNode stringValue(String s) {
        return Catalog.JSON.createObjectNode().put("type", "string").put("value", s);
    }

    static byte[] attribute(int name, JsonNode typed) {
        return new ProtoWriter().sint32(1, name).bytes(2, object(typed)).toByteArray();
    }

    static byte[] object(JsonNode typed) {
        String type = typed.path("type").asText();
        JsonNode v = typed.path("value");
        ProtoWriter o = new ProtoWriter();
        switch (type) {
            case "json" -> o.int32(1, T_JSON).string(4, v.isTextual() ? v.asText() : v.toString());
            case "string" -> o.int32(1, T_STRING).string(4, v.asText());
            case "int" -> o.int32(1, T_INT).int32(6, (int) v.asLong());
            case "float" -> o.int32(1, T_FLOAT).float32(7, (float) v.asDouble());
            case "bool" -> o.int32(1, T_BOOL).bool(5, v.asBoolean());
            case "uuid" -> o.int32(1, T_UUID).bytes(8, uuid(v.asText()));
            default -> { /* unknown type: send an empty Object, as the Python writer sends nothing */ }
        }
        return o.toByteArray();
    }

    /** dwd.Protobuf.UUID: lo = low 8 bytes, hi = high 8 bytes of the big-endian GUID. */
    static byte[] uuid(String guid) {
        UUID u = UUID.fromString(guid);
        return new ProtoWriter().fixed64(1, u.getLeastSignificantBits()).fixed64(2, u.getMostSignificantBits())
                .toByteArray();
    }

    static byte[] uuidBytes(String guid) {
        UUID u = UUID.fromString(guid);
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
    }
}
