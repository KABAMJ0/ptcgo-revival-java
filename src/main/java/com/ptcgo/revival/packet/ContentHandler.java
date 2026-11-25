package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.ScenarioEncoder;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.DynamicPageRepository;
import com.ptcgo.revival.db.SocialRepository;
import com.ptcgo.revival.net.ProtoEnvelope;
import com.ptcgo.revival.net.WargFlags;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Landing pages, notifications, achievements and the league scenario list. */
public final class ContentHandler implements PacketHandler {
    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("GetDynamicPages", ContentHandler::dynamicPages);
        m.put("GetNotifications", ContentHandler::notifications);
        m.put("GetAchievements", (c, v, rid, f) -> c.send("ListAchievements", Map.of("achievements", List.of()), rid));
        m.put("GetProtobufScenarios", (c, v, rid, f) -> c.sendRaw(
                ProtoEnvelope.wrap(ScenarioEncoder.MESSAGE_NAME, ScenarioEncoder.allScenarios()), rid, WargFlags.PROTOBUF));
        return m;
    }

    private static Database db(ClientContext c) {
        return c.services().players().database();
    }

    /** The Unity landing-page model does no start/end filtering itself, so the server does: end <= 0 means open-ended. */
    static boolean active(JsonNode content, long nowMs) {
        long start = content.path("startTime").asLong(0);
        long end = content.path("endTime").asLong(0);
        return start <= nowMs && (end <= 0 || nowMs < end);
    }

    private static void dynamicPages(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        List<JsonNode> pages = new ArrayList<>(), maintenance = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (DynamicPageRepository.Page p : new DynamicPageRepository(db(c)).list(true)) {
            ObjectNode content = p.content().deepCopy();
            // The column is the admin-facing source of truth; a stale embedded sortOrder must not win on the wire.
            content.put("sortOrder", p.sortOrder());
            if (p.pageType().equals("maintenance")) {
                // Maintenance timestamps are event dates the native window prints: announcing an upcoming window is
                // intentional, so only landing pages are filtered.
                maintenance.add(content);
            } else if (active(content, now)) {
                pages.add(content);
            }
        }
        pages.sort(Comparator.comparingInt(p -> p.path("sortOrder").asInt(0)));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("pageData", pages);
        res.put("maintenanceData", maintenance);
        c.send("DynamicLandingPages", res, rid);
    }

    private static void notifications(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        List<Map<String, Object>> list = new ArrayList<>();
        for (SocialRepository.Invite invite : new SocialRepository(db(c)).incomingInvites(c.account().accountId())) {
            // The payload rides as a JSON string the client parses with its polymorphic deserializer.
            Map<String, Object> friend = new LinkedHashMap<>();
            friend.put("accountID", invite.fromAccountId());
            friend.put("displayName", invite.displayName());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", "FriendInvitation");
            payload.put("value", Map.of("friend", friend));
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("notificationID", UUID.randomUUID().toString());
            try {
                n.put("payload", com.ptcgo.revival.net.WargCodec.JSON.writeValueAsString(payload));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            n.put("notificationType", 0);
            list.add(n);
        }
        c.send("NotificationsRequested", Map.of("notificationList", list), rid);
    }
}
