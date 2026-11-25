package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.social.ChatRooms;
import com.ptcgo.revival.social.GameChatRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Private chat, lobby rooms and in-game chat. Port of ptcgo/packets/handlers/social.py (chat part) and
 * GameChat from gameplay.py.
 *
 * <p>Not ported: the Python chat_log (bounded ring plus daily JSONL moderation files read by the admin panel).
 * Lines are logged through SLF4J on the {@code chat} logger instead. The Python server enforces no chat-mode
 * attribute either, so none is checked here.
 */
public final class ChatHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ChatHandler.class);
    private static final Logger CHAT_LOG = LoggerFactory.getLogger("chat");

    /** Lobby rooms offered to every client (RoomType.Lobby == 0). */
    static final List<Map<String, Object>> LOBBY_ROOMS = List.of(room("Lobby_Spirit_1", "Spirit Lobby", 0));

    private static Map<String, Object> room(String id, String name, int type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomID", id);
        m.put("displayName", name);
        m.put("roomType", type);
        return m;
    }

    private final ChatRooms rooms;
    private final GameChatRouter gameChat;

    public ChatHandler(ChatRooms rooms, GameChatRouter gameChat) {
        this.rooms = rooms;
        this.gameChat = gameChat;
    }

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("PrivateChat", this::privateChat);
        m.put("PublicRooms", (c, v, rid, f) -> c.send("AvailableRooms", Map.of("rooms", LOBBY_ROOMS), rid));
        m.put("JoinRoom", this::joinRoom);
        m.put("LeaveRoom", this::leaveRoom);
        m.put("Chat", this::roomChat);
        m.put("GameChat", this::gameChat);
        return m;
    }

    private static Map<String, Object> userInfo(ClientContext c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountID", c.account().accountId());
        m.put("displayName", c.account().displayName());
        return m;
    }

    private static Object field(JsonNode v, String name) {
        return v != null && v.hasNonNull(name) ? v.get(name).asText() : null;
    }

    private void privateChat(ClientContext c, JsonNode v, int rid, int flags) {
        if (c.account() == null) return;
        String targetId = (String) field(v, "accountID");
        Object message = field(v, "message");
        Object purpose = v != null && v.hasNonNull("purpose") ? v.get("purpose").asText() : "";
        ClientContext target = targetId == null ? null : c.services().registry().find(targetId).orElse(null);

        Map<String, Object> to = new LinkedHashMap<>();
        to.put("accountID", targetId);
        to.put("displayName", target != null ? target.account().displayName() : "Offline Player");
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("userInfo", userInfo(c));
        notify.put("toUserInfo", to);
        notify.put("message", message);
        notify.put("purpose", purpose);
        notify.put("targetWasOnline", target != null);

        LOG.info("[TCP] [{}] Chat from '{}' to '{}': {}", c.remoteAddress(), c.account().displayName(), targetId, message);
        CHAT_LOG.info("private {}({}) -> {}({}): {}", c.account().displayName(), c.account().accountId(),
                to.get("displayName"), targetId, message);

        c.send("NotifyPrivateChat", notify, 0); // echo so the sender sees their own line
        if (target != null) target.send("NotifyPrivateChat", notify, 0);
    }

    private static boolean isLobby(String roomId) {
        return LOBBY_ROOMS.stream().anyMatch(r -> r.get("roomID").equals(roomId));
    }

    private void joinRoom(ClientContext c, JsonNode v, int rid, int flags) {
        String roomId = (String) field(v, "roomID");
        if (!isLobby(roomId) || c.account() == null) return;
        Map<String, Object> room = LOBBY_ROOMS.stream().filter(r -> r.get("roomID").equals(roomId)).findFirst().orElseThrow();
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("roomID", roomId);
        notify.put("userInfo", userInfo(c));
        for (ClientContext m : rooms.members(roomId)) m.send("NotifyJoin", notify, 0);
        rooms.join(roomId, c);
        List<Map<String, Object>> members = new ArrayList<>();
        for (ClientContext m : rooms.members(roomId)) members.add(userInfo(m));
        Map<String, Object> connected = new LinkedHashMap<>();
        connected.put("room", room);
        connected.put("members", members);
        connected.put("messageHistory", List.of());
        c.send("ChatConnected", connected, rid);
    }

    private void leaveRoom(ClientContext c, JsonNode v, int rid, int flags) {
        String roomId = (String) field(v, "roomID");
        if (roomId != null) rooms.leave(roomId, c);
        c.send("ChatDisconnected", mapOf("roomID", roomId), rid);
        if (c.account() == null || roomId == null) return;
        Map<String, Object> notify = mapOf("roomID", roomId);
        notify.put("accountID", c.account().accountId());
        for (ClientContext m : rooms.members(roomId)) m.send("NotifyLeave", notify, 0);
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private void roomChat(ClientContext c, JsonNode v, int rid, int flags) {
        String roomId = (String) field(v, "roomID");
        Object message = v != null && v.hasNonNull("message") ? v.get("message").asText() : "";
        if (c.account() == null || roomId == null || !rooms.isMember(roomId, c)) return;
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("roomID", roomId);
        notify.put("userInfo", userInfo(c));
        notify.put("message", message);
        CHAT_LOG.info("room {} {}({}): {}", roomId, c.account().displayName(), c.account().accountId(), message);
        for (ClientContext m : rooms.members(roomId)) m.send("NotifyChat", notify, 0); // the sender renders from this too
    }

    private void gameChat(ClientContext c, JsonNode v, int rid, int flags) {
        if (c.account() == null) return;
        String gameId = (String) field(v, "gameID");
        Object message = field(v, "message");
        LOG.info("[Gameplay] Player {} sent GameChat in {}: {}", c.account().username(), gameId, message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gameID", gameId);
        payload.put("userInfo", userInfo(c));
        payload.put("message", message);
        for (ClientContext r : gameChat.recipients(c.account().accountId(), gameId)) r.send("NotifyGameChat", payload, 0);
    }
}
