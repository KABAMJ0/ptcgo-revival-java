package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dispatches inbound JSON messages to registered handlers by their "name". */
public final class PacketRouter {
    private static final Logger LOG = LoggerFactory.getLogger(PacketRouter.class);
    private final Map<String, PacketHandler.MessageHandler> table = new HashMap<>();

    public PacketRouter(List<PacketHandler> groups) {
        for (PacketHandler group : groups) table.putAll(group.handlers());
    }

    public void route(ClientContext client, JsonNode message, int requestId, int flags) {
        String name = message != null && message.hasNonNull("name") ? message.get("name").asText() : null;
        if (name == null && message != null && message.has("queueName") && message.has("gameID") && message.has("path")) {
            // Unwrapped JSON: duck-typed as ReadyForMatchConfirmation, like the Python router.
            PacketHandler.MessageHandler ready = table.get("ReadyForMatchConfirmation");
            if (ready != null) {
                try {
                    ready.handle(client, message, requestId, flags);
                } catch (Exception e) {
                    LOG.error("[TCP] [{}] Error in handler ReadyForMatchConfirmation", client.remoteAddress(), e);
                }
                return;
            }
        }
        if (name == null) {
            LOG.warn("[TCP] [{}] Unhandled packet (no message name)", client.remoteAddress());
            return;
        }
        PacketHandler.MessageHandler handler = table.get(name);
        if (handler == null) {
            LOG.warn("[TCP] [{}] No handler registered for message: {}", client.remoteAddress(), name);
            return;
        }
        try {
            handler.handle(client, message.get("value"), requestId, flags);
        } catch (Exception e) {
            LOG.error("[TCP] [{}] Error in handler {}", client.remoteAddress(), name, e);
        }
    }

    public boolean handles(String name) {
        return table.containsKey(name);
    }
}
