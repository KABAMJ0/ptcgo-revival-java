package com.ptcgo.revival.packet;

import java.util.Map;

/** A group of message handlers; each entry maps an inbound message name to its handler. */
public interface PacketHandler {
    Map<String, MessageHandler> handlers();

    @FunctionalInterface
    interface MessageHandler {
        void handle(ClientContext client, com.fasterxml.jackson.databind.JsonNode value, int requestId, int flags)
                throws Exception;
    }
}
