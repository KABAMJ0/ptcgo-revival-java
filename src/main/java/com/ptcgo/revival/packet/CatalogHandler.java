package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.catalog.ArchetypeEncoder;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.net.ProtoEnvelope;
import com.ptcgo.revival.net.WargFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Card and product catalogue downloads (protobuf {@code ArchetypesFound}). */
public final class CatalogHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogHandler.class);

    // ProductType values the client asks for by category key
    static final int PRODUCT_COINS = 5;
    static final int PRODUCT_DECK_BOX = 11;
    static final int PRODUCT_SLEEVE = 27;

    @Override
    public Map<String, MessageHandler> handlers() {
        return Map.of("GetProtobufArchetypesList", CatalogHandler::archetypesList);
    }

    static boolean productMatches(Catalog.Product p, String key) {
        return p.key().equals(key)
                || (key.equals("cardSleeves") && p.productType() == PRODUCT_SLEEVE)
                || (key.equals("coins") && p.productType() == PRODUCT_COINS)
                || (key.equals("deckBoxes") && p.productType() == PRODUCT_DECK_BOX);
    }

    private static void archetypesList(ClientContext c, JsonNode v, int rid, int f) {
        String key = v != null && v.hasNonNull("key") ? v.get("key").asText() : "unknown";
        Catalog catalog = c.services().catalog();
        ClientState state = c.state();
        // A time-based checksum makes the client reload archetypes after an hour, as the Python server does.
        String checksum = "spirit_" + key + "_" + System.currentTimeMillis() / 1000 / 3600;
        int count = 0;

        byte[] cards = new byte[0];
        Set<String> cardGuids = catalog.guidsInSet(key);
        // Cards never span keys, so per-key dedup is equivalent to per-guid dedup.
        if (!cardGuids.isEmpty() && state.sentArchetypeKeys.add(key)) {
            cards = catalog.encoder().cardArchetypes(key);
            state.sentArchetypes.addAll(cardGuids);
            count += cardGuids.size();
        }

        List<byte[]> products = new ArrayList<>();
        for (Catalog.Product p : catalog.products()) {
            if (productMatches(p, key) && state.sentArchetypes.add(p.guid())) {
                products.add(catalog.encoder().productArchetype(p, key));
                count++;
            }
        }

        byte[] body = ProtoEnvelope.wrap(ArchetypeEncoder.MESSAGE_NAME,
                ArchetypeEncoder.found(cards, products, checksum, key));
        int flags = WargFlags.PROTOBUF | WargFlags.COMPRESSED;
        c.sendRaw(body, rid, flags);
        LOG.info("[TCP] [{}] Sent {} archetypes for key {} ({} bytes before compression)",
                c.remoteAddress(), count, key, body.length);
    }
}
