package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.ArchetypeEncoder;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.net.ProtoEnvelope;
import com.ptcgo.revival.net.WargFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalogue-wide data that does not depend on the player: archetype keys, sets, family maps, format legality,
 * localizations, MOTD, feature flags, versions and the avatar item list. The content comes from the Python
 * exporter's payloads (see {@link com.ptcgo.revival.catalog.StaticPayloads}).
 */
public final class StaticDataHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(StaticDataHandler.class);

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("GetArchetypeListKeys", (c, v, rid, f) ->
                c.send("ArchetypeKeys", Map.of("keys", payload(c, "archetype_keys", emptyArray())), rid));
        m.put("GetSetData", StaticDataHandler::setData);
        m.put("GetPokemonFamilyMap", (c, v, rid, f) ->
                c.send("PokemonFamilyMap", Map.of("pokemonFamilyMap", payload(c, "family_map", emptyObject())), rid));
        m.put("GetArchetypeIDsByFamily", (c, v, rid, f) ->
                c.send("ArchetypeIDsByFamily", Map.of("familyMap", payload(c, "family_id_map", emptyObject())), rid));
        m.put("GetFormatLegalityForArchetypes", (c, v, rid, f) ->
                c.send("FormatLegalityForArchetypes", Map.of("archLegality", payload(c, "format_legality", emptyArray())), rid));
        m.put("GetAllLocalizationReleases", StaticDataHandler::localizations);
        m.put("GetMotd", (c, v, rid, f) -> misc(c, "motd", rid));
        m.put("GetFeatureStatuses_v2", (c, v, rid, f) -> misc(c, "feature_statuses_v2", rid));
        m.put("GetDynamicVersions", (c, v, rid, f) -> misc(c, "dynamic_versions", rid));
        m.put("GetAllBannedCardsByFormats", (c, v, rid, f) -> misc(c, "banned_cards_by_format", rid));
        m.put("GetTimeLockedArchetypes", (c, v, rid, f) -> c.send("TimeLockedArchetypes",
                Map.of("currentServerTime", System.currentTimeMillis() / 1000, "timeLockedArchetypes", Map.of()), rid));
        m.put("GetProtobufAllAvatarArchetypesList", StaticDataHandler::avatars);
        return m;
    }

    private static JsonNode emptyArray() {
        return Catalog.JSON_FACTORY.arrayNode();
    }

    private static JsonNode emptyObject() {
        return Catalog.JSON_FACTORY.objectNode();
    }

    private static JsonNode payload(ClientContext c, String name, JsonNode fallback) {
        return c.services().catalog().payloads().get(name).orElse(fallback);
    }

    /** SetDataList wrapped in a ChecksumDiff; nested objects are wrapped by hand (only the top level is). */
    private static void setData(ClientContext c, JsonNode v, int rid, int f) {
        JsonNode sets = payload(c, "sets", emptyObject());
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("groupKey", "all");
        info.put("ckSum", sets.path("checksum").asText(""));
        info.put("newData", Map.of("name", "SetDataList", "value",
                Map.of("setDataList", sets.path("setDataList").isMissingNode() ? emptyArray() : sets.get("setDataList"))));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("dataType", "GetSetData");
        res.put("ckSumDiff", List.of(Map.of("name", "ChecksumDiffInfo", "value", info)));
        c.send("ChecksumDiff", res, rid);
    }

    /** Sends the full release unless the client already holds a matching md5. */
    private static void localizations(ClientContext c, JsonNode v, int rid, int f) {
        Optional<JsonNode> meta = c.services().catalog().payloads().get("localizations_meta");
        String locale = v != null && v.hasNonNull("locale") ? v.get("locale").asText() : "en_US";
        String md5 = meta.map(m -> m.path("md5").asText("spirit_hash_0")).orElse("spirit_hash_0");
        Map<String, Object> releases = new LinkedHashMap<>();
        JsonNode client = v == null ? null : v.get("keyedChecksums");
        if (client == null || !md5.equals(client.path("core").asText(null))) {
            releases.put("core", Map.of("md5", md5, "localizationList", payload(c, "localizations", emptyArray())));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("locale", locale);
        res.put("version", meta.map(m -> m.path("version").asText("spirit_v1")).orElse("spirit_v1"));
        res.put("releases", releases);
        c.send("AllLocalizationReleases", res, rid);
    }

    /** Exported responses carry their own messageName; the rest of the object is the payload. */
    private static void misc(ClientContext c, String key, int rid) {
        JsonNode all = payload(c, "misc", emptyObject()).get(key);
        if (!(all instanceof ObjectNode obj)) {
            LOG.warn("[TCP] [{}] misc payload '{}' missing", c.remoteAddress(), key);
            return;
        }
        ObjectNode copy = obj.deepCopy();
        String name = copy.remove("messageName").asText();
        Map<String, Object> body = new LinkedHashMap<>();
        copy.fields().forEachRemaining(e -> body.put(e.getKey(), e.getValue()));
        c.send(name, body, rid);
    }

    private static void avatars(ClientContext c, JsonNode v, int rid, int f) {
        JsonNode avatars = payload(c, "avatar_archetypes", emptyObject()).path("archetypes");
        // Hourly checksum, as the Python server sends, so the client refreshes its avatar items periodically.
        String checksum = "spirit_avatars_" + System.currentTimeMillis() / 1000 / 3600;
        byte[] body = ProtoEnvelope.wrap(ArchetypeEncoder.AVATAR_MESSAGE_NAME, ArchetypeEncoder.avatars(avatars, checksum));
        c.sendRaw(body, rid, WargFlags.PROTOBUF);
    }
}
