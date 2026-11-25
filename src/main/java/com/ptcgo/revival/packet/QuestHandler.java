package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.db.QuestRepository.Snapshot;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Daily challenge requests (ptcgo/packets/handlers/quests.py). */
public final class QuestHandler implements PacketHandler {
    @Override
    public Map<String, MessageHandler> handlers() {
        return Map.of(
                "GetQuests", QuestHandler::getQuests,
                "GetQuestConfigurationData", QuestHandler::getConfiguration,
                "QuestsEnabled", QuestHandler::questsEnabled,
                "QuestsSelected", (c, v, rid, f) -> {
                    if (c.account() != null) sendSnapshot(c, c.services().progression().quests().select(c.account().accountId(), ids(v)), rid);
                },
                "AbandonQuests", (c, v, rid, f) -> {
                    if (c.account() != null) sendSnapshot(c, c.services().progression().quests().abandon(c.account().accountId(), ids(v)), rid);
                });
    }

    /** QuestConfigurationUpdated first, then AllQuests answering the request. */
    static void sendSnapshot(ClientContext c, Snapshot snap, int rid) {
        c.send("QuestConfigurationUpdated", Map.of("questConfiguration", snap.configuration()), 0);
        Map<String, Object> all = new LinkedHashMap<>();
        snap.quests().fields().forEachRemaining(e -> all.put(e.getKey(), e.getValue()));
        c.send("AllQuests", all, rid);
    }

    private static void getQuests(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        sendSnapshot(c, c.services().progression().quests().get(c.account().accountId()), rid);
    }

    private static void getConfiguration(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        Snapshot snap = c.services().progression().quests().get(c.account().accountId());
        c.send("QuestConfigurationUpdated", Map.of("questConfiguration", snap.configuration()), rid);
    }

    private static void questsEnabled(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null || v == null || !v.isObject()) return;
        if (!v.path("enabled").isBoolean()) {
            getQuests(c, v, rid, f);
            return;
        }
        Snapshot snap = c.services().progression().quests().setEnabled(c.account().accountId(), v.get("enabled").asBoolean());
        c.send("AccountUpdated", Map.of("account", AccountAttributes.accountObject(c.services(), c.account())), 0);
        sendSnapshot(c, snap, rid);
    }

    /** The list of quest ids, or empty unless every entry is a string. */
    static List<String> ids(JsonNode v) {
        List<String> out = new ArrayList<>();
        JsonNode q = v == null ? null : v.get("quests");
        if (q == null || !q.isArray()) return out;
        for (JsonNode n : q) {
            if (!n.isTextual()) return new ArrayList<>();
            out.add(n.asText());
        }
        return out;
    }
}
